package com.nuvio.app.features.iptv

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.epg.EpgMirrorDbDriver
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.identity.IptvIdentity
import com.nuvio.app.features.iptv.match.MatchDbDriver
import com.nuvio.app.features.iptv.overlay.ChannelOverlay
import com.nuvio.app.features.iptv.overlay.IptvOverlayStore
import com.nuvio.app.features.iptv.overlay.OverlayDbDriver
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step 0 through the real overlay SQLite store and the real M3U file store (desktop twin of NuvioMobile's
 * Robolectric `PlaylistKeyStoresTest`). The library / watched / recents moves run the identical
 * commonMain code proven there; they are not seeded here because on desktop those repositories persist
 * into the developer's real app-data directory (no test seam), which a unit test must not write.
 */
class PlaylistKeyStoresTest {

    private lateinit var playlistsDir: File
    private val profile get() = ProfileRepository.activeProfileId

    @BeforeTest
    fun setUp() {
        playlistsDir = Files.createTempDirectory("playlists").toFile()
        m3uPlaylistsDirForTests = playlistsDir
        OverlayDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        // The adoption's cache purge touches these stores too — keep them in memory.
        IptvContentDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        MatchDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        EpgMirrorDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        XtreamRepository.persistWriteForTest = { _, _ -> }
    }

    @AfterTest
    fun tearDown() {
        XtreamRepository.verifyForTest = null
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.installAccountsForTest(emptyList())
        m3uPlaylistsDirForTests = null
        playlistsDir.deleteRecursively()
    }

    @Test
    fun `hidden channels survive a provider domain change`() = runBlocking {
        val old = XtreamAccount(id = "http://old.example:8080|u", name = "P", baseUrl = "http://old.example:8080", username = "u", password = "p")
        XtreamRepository.installAccountsForTest(listOf(old))
        XtreamRepository.verifyForTest = { Result.success(Unit) }
        val hiddenBefore = IptvIdentity.entityId(old.id, "BBC One HD", "bbc.uk")
        IptvOverlayStore.setChannel(profile, hiddenBefore, old.id, ChannelOverlay(hidden = true), 1L)

        val done = CompletableDeferred<Boolean>()
        XtreamRepository.editFromForm(
            old.id,
            XtreamFormInput(
                serverUrl = "http://new-domain.example:8080", username = "u", password = "p", name = "P",
                epgUrl = null, dnsProvider = "system", autoRefreshHours = 24,
            ),
        ) { done.complete(it) }
        assertTrue(withTimeout(10_000) { done.await() })

        val edited = XtreamRepository.uiState.value.accounts.single()
        assertEquals("http://new-domain.example:8080", edited.baseUrl)
        val hiddenAfter = IptvIdentity.entityId(edited.id, "BBC One HD", "bbc.uk")
        assertEquals(hiddenBefore, hiddenAfter, "the channel's overlay key is unchanged")
        assertTrue(IptvOverlayStore.snapshot(profile).channels[hiddenAfter]?.hidden == true, "the hidden channel stays hidden")
    }

    @Test
    fun `adopting a server key moves the file copy and the next pull is a no-op`() = runBlocking {
        val local = XtreamAccount(
            id = "m3u_file|tv.m3u|1719000000000", name = "TV", baseUrl = "", username = "", password = "",
            sourceType = SOURCE_TYPE_M3U_FILE, fileName = "tv.m3u",
        )
        XtreamRepository.installAccountsForTest(listOf(local))
        val bytes = "#EXTM3U\n#EXTINF:-1,BBC\nhttp://x/1.ts\n"
        copyM3UFileToStorage(local.id, PickedM3UFile("tv.m3u") { bytes.encodeToByteArray() })

        val serverKey = "m3u_file|tv.m3u|synced"
        val pulled = listOf(PulledPlaylist(local.copy(id = serverKey), serverKeyed = true))
        val first = XtreamRepository.adoptFromPull(profile, pulled)

        assertEquals(listOf(PlaylistKeyAdoption.Rekey(local.id, serverKey)), first.rekeys)
        assertEquals(listOf(serverKey), XtreamRepository.uiState.value.accounts.map { it.id })
        assertEquals(bytes, File(m3uFileStoragePath(serverKey)).readText(), "the file copy followed the id")
        assertFalse(File(m3uFileStoragePath(local.id)).exists(), "and was moved, not copied")
        assertEquals(emptyList(), XtreamRepository.adoptFromPull(profile, pulled).rekeys, "the next pull re-keys nothing")
    }
}
