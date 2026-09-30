package com.nuvio.app.features.plugins

import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.SyncBackendConfig
import com.nuvio.app.core.network.SyncBackendDefaults
import com.nuvio.app.core.network.SyncBackendRepository
import com.nuvio.app.core.network.SyncBackendStorage
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.russhwolf.settings.SettingsInitializer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginSyncTest {
    private val server = MockWebServer()
    private val manifestUrl get() = server.url("/plugin/manifest.json").toString()

    @Before
    fun setUp(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SettingsInitializer().create(context)
        PluginStorage.initialize(context)
        AddonStorage.initialize(context)
        SyncBackendStorage.initialize(context)
        PluginRepository.clearLocalState()
        AddonRepository.clearLocalState()
        server.start()
        // Point the Supabase client at the mock server (held in memory; only the id is persisted).
        SyncBackendRepository.applyBackendAfterLogout(
            SyncBackendConfig(
                id = "test",
                displayName = "Test",
                supabaseUrl = server.url("/").toString(),
                anonKey = "test-key",
                avatarPublicBaseUrl = server.url("/").toString(),
            ),
            revision = "test",
        )
        SupabaseProvider.rebuildClient()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        PluginRepository.clearLocalState()
        AddonRepository.clearLocalState()
        SyncBackendRepository.applyBackendAfterLogout(SyncBackendDefaults.hosted(), revision = "")
        SupabaseProvider.rebuildClient()
        server.shutdown()
    }

    @Test
    fun emptyRemoteRemovesCachedPluginsWithoutUploadingAndStaysEmptyAfterReload(): Unit = runBlocking {
        // A device that last agreed with the server on this list: the server's delete-all must stick.
        seedPlugins(synced = true)
        PluginRepository.initialize()
        assertEquals(1, PluginRepository.uiState.value.repositories.size)
        respond("[]")

        PluginRepository.pullFromServer(1)

        assertTrue(PluginRepository.uiState.value.repositories.isEmpty())
        assertTrue(PluginRepository.uiState.value.scrapers.isEmpty())
        val stored = Json.decodeFromString<StoredPluginsState>(assertNotNull(PluginStorage.loadState(1)))
        assertTrue(stored.repositories.isEmpty())
        assertTrue(stored.scrapers.isEmpty())
        PluginRepository.clearLocalState()
        PluginRepository.initialize()
        respond("[]")
        PluginRepository.pullFromServer(1)

        assertTrue(PluginRepository.uiState.value.repositories.isEmpty())
        assertPullsOnly("plugins", count = 2)
    }

    @Test
    fun nonemptyRemoteRemovesOnlyDeletedPluginsWithoutUploading(): Unit = runBlocking {
        val retainedUrl = server.url("/retained/manifest.json").toString()
        seedPlugins(listOf(manifestUrl, retainedUrl), synced = true)
        respond("""[{"url":"$retainedUrl"}]""")

        PluginRepository.pullFromServer(1)

        assertEquals(listOf(retainedUrl), PluginRepository.uiState.value.repositories.map { it.manifestUrl })
        assertEquals(listOf(retainedUrl), PluginRepository.uiState.value.scrapers.map { it.repositoryUrl })
        assertPullsOnly("plugins")
    }

    @Test
    fun failedPullPreservesCachedPluginsWithoutUploading(): Unit = runBlocking {
        seedPlugins()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))

        PluginRepository.pullFromServer(1)

        assertEquals(listOf(manifestUrl), PluginRepository.uiState.value.repositories.map { it.manifestUrl })
        val stored = Json.decodeFromString<StoredPluginsState>(assertNotNull(PluginStorage.loadState(1)))
        assertEquals(listOf(manifestUrl), stored.repositories.map { it.manifestUrl })
        assertPullsOnly("plugins")
    }

    @Test
    fun automaticRefreshDoesNotUploadPlugins(): Unit = runBlocking {
        seedPlugins()
        respond(
            """{"name":"Refreshed plugin","version":"1","scrapers":[{"id":"scraper","name":"Scraper","version":"1","filename":"scraper.js"}]}""",
        )
        respond("module.exports = {}")

        PluginRepository.refreshAll()
        withTimeout(5_000) {
            PluginRepository.uiState.first { state -> state.repositories.none { it.isRefreshing } }
        }

        assertEquals("Refreshed plugin", PluginRepository.uiState.value.repositories.single().name)
        repeat(2) {
            assertEquals("GET", assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).method)
        }
        assertNull(server.takeRequest(750, TimeUnit.MILLISECONDS))
    }

    @Test
    fun removalWhileSignedOutIsNotUploadedButTheNextPullCarriesItToTheServer(): Unit = runBlocking {
        // B79: an edit whose push is refused (no session) used to be overwritten by the next pull.
        seedPlugins(synced = true)

        PluginRepository.removeRepository(manifestUrl)
        assertNull(server.takeRequest(750, TimeUnit.MILLISECONDS))

        respond("""[{"url":"$manifestUrl"}]""")
        respond("")
        PluginRepository.pullFromServer(1)

        assertTrue(PluginRepository.uiState.value.repositories.isEmpty())
        assertEquals("GET", assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).method)
        val push = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/rest/v1/rpc/sync_push_plugins", push.requestUrl?.encodedPath)
        assertTrue(push.body.readUtf8().contains("\"p_plugins\":[]"))
        assertEquals(emptyList(), PluginStorage.loadSyncedRepositoryUrls(1))
    }

    @Test
    fun aNeverSyncedDeviceKeepsItsPluginsOnAnEmptyServerAndUploadsThem(): Unit = runBlocking {
        seedPlugins()
        respond("[]")
        respond("")

        PluginRepository.pullFromServer(1)

        assertEquals(listOf(manifestUrl), PluginRepository.uiState.value.repositories.map { it.manifestUrl })
        assertEquals("GET", assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).method)
        val push = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/rest/v1/rpc/sync_push_plugins", push.requestUrl?.encodedPath)
        assertTrue(push.body.readUtf8().contains(manifestUrl))
        assertEquals(listOf(manifestUrl), PluginStorage.loadSyncedRepositoryUrls(1))
    }

    @Test
    fun aNeverSyncedDeviceKeepsItsAddonsOnAnEmptyServerAndUploadsThem(): Unit = runBlocking {
        // The B79 field report: desktop addons never reached the account, and the pull wiped them.
        AddonStorage.saveInstalledAddonUrls(1, listOf(manifestUrl))
        AddonRepository.initialize()
        respond("[]")
        respond("")

        AddonRepository.pullFromServer(1)

        assertEquals(listOf(manifestUrl), AddonRepository.uiState.value.addons.map { it.manifestUrl })
        assertEquals("GET", assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).method)
        val push = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/rest/v1/rpc/sync_push_addons", push.requestUrl?.encodedPath)
        assertTrue(push.body.readUtf8().contains(manifestUrl))
        assertEquals(listOf(manifestUrl), AddonStorage.loadSyncedAddonUrls(1))
    }

    @Test
    fun emptyRemoteRemovesCachedAddonsWithoutUploadingAndStaysEmptyAfterReload(): Unit = runBlocking {
        AddonStorage.saveInstalledAddonUrls(1, listOf(manifestUrl))
        AddonStorage.saveAddonEnabledStates(1, mapOf(manifestUrl to false))
        AddonStorage.saveSyncedAddonUrls(1, listOf(manifestUrl))
        AddonRepository.initialize()
        assertEquals(1, AddonRepository.uiState.value.addons.size)
        respond("[]")

        AddonRepository.pullFromServer(1)

        assertTrue(AddonRepository.uiState.value.addons.isEmpty())
        assertTrue(AddonStorage.loadInstalledAddonUrls(1).isEmpty())
        assertTrue(AddonStorage.loadAddonEnabledStates(1).isEmpty())
        AddonRepository.clearLocalState()
        AddonRepository.initialize()
        respond("[]")
        AddonRepository.pullFromServer(1)

        assertTrue(AddonRepository.uiState.value.addons.isEmpty())
        assertPullsOnly("addons", count = 2)
    }

    private fun seedPlugins(urls: List<String> = listOf(manifestUrl), synced: Boolean = false) {
        if (synced) PluginStorage.saveSyncedRepositoryUrls(1, urls)
        val state = PluginsUiState(
            repositories = urls.map { url ->
                PluginRepositoryItem(
                    manifestUrl = url,
                    name = "Cached plugin",
                    scraperCount = 1,
                    lastUpdated = System.currentTimeMillis(),
                )
            },
            scrapers = urls.map { url ->
                PluginScraper(
                    id = "$url:scraper",
                    repositoryUrl = url,
                    name = "Cached scraper",
                    description = "",
                    version = "1.0.0",
                    filename = "scraper.js",
                    supportedTypes = listOf("movie"),
                    enabled = true,
                    manifestEnabled = true,
                    code = "module.exports = {}",
                )
            },
        )
        state.scrapers.forEach { scraper ->
            assertTrue(PluginStorage.saveScraperCode(1, scraper.id, scraper.code, overwrite = true))
        }
        PluginStorage.saveState(1, Json.encodeToString(state.toStoredPluginsState()))
    }

    private fun respond(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun assertPullsOnly(table: String, count: Int = 1) {
        repeat(count) {
            val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/rest/v1/$table", request.requestUrl?.encodedPath)
        }
        assertNull(server.takeRequest(750, TimeUnit.MILLISECONDS))
    }
}
