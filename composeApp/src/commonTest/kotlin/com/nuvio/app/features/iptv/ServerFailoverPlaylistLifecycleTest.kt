package com.nuvio.app.features.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 0.3 — the failover state's lifecycle through the real repository paths: the removal plan clears
 * it (a cache: on a user delete AND on a sync-pull removal), and editing the server list resets it to
 * the main server. Also: the add/edit form carries the validated backup list onto the playlist.
 */
class ServerFailoverPlaylistLifecycleTest {

    private lateinit var store: InMemoryServerFailoverStateStore
    private val onBackup = ServerFailoverState(activeIndex = 1, mainRetryAfterMs = 99_000L)

    private fun account(tag: String, backups: List<String> = listOf("http://$tag-b1.example")) = XtreamAccount(
        id = "http://$tag.example|u", name = tag, baseUrl = "http://$tag.example", username = "u", password = "p",
        backupUrls = backups,
    )

    @BeforeTest
    fun setUp() {
        store = InMemoryServerFailoverStateStore()
        PlaylistServerFailover.installForTest(store = store, clock = { 1_000L }, profileId = { 1 })
        XtreamRepository.persistWriteForTest = { _, _ -> }
    }

    @AfterTest
    fun tearDown() {
        PlaylistServerFailover.resetForTest()
        XtreamRepository.verifyForTest = null
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
    }

    @Test
    fun `the removal plan treats failover state as a cache`() {
        assertTrue(PlaylistRemovalTarget.ServerFailover in PlaylistRemovalCleanup.plan(PlaylistRemovalOrigin.UserDelete))
        assertTrue(PlaylistRemovalTarget.ServerFailover in PlaylistRemovalCleanup.plan(PlaylistRemovalOrigin.SyncPull))
    }

    @Test
    fun `deleting a playlist clears its failover state and spares the others`() {
        val doomed = account("doomed")
        val keeper = account("keeper")
        XtreamRepository.installAccountsForTest(listOf(doomed, keeper))
        store.write(1, doomed.id, onBackup)
        store.write(1, keeper.id, onBackup)

        XtreamRepository.remove(doomed.id)

        assertEquals(ServerFailoverState(), store.read(1, doomed.id))
        assertEquals(onBackup, store.read(1, keeper.id))
    }

    @Test
    fun `a playlist dropped by a sync pull clears its failover state`() {
        val doomed = account("pulled")
        val keeper = account("kept")
        XtreamRepository.installAccountsForTest(listOf(doomed, keeper))
        store.write(1, doomed.id, onBackup)
        store.write(1, keeper.id, onBackup)

        XtreamRepository.applyFromRemote(1, listOf(keeper))

        assertEquals(ServerFailoverState(), store.read(1, doomed.id))
        assertEquals(onBackup, store.read(1, keeper.id))
    }

    private fun edit(old: XtreamAccount, backups: List<String>, name: String = old.name): Boolean = runBlocking {
        val done = CompletableDeferred<Boolean>()
        XtreamRepository.editFromForm(
            old.id,
            XtreamFormInput(
                serverUrl = old.baseUrl, username = old.username, password = old.password, name = name,
                epgUrl = null, dnsProvider = "system", autoRefreshHours = 24, backupUrls = backups,
            ),
        ) { done.complete(it) }
        withTimeout(10_000) { done.await() }
    }

    @Test
    fun `editing the server list saves it and resets to the main server`() {
        val old = account("edited")
        XtreamRepository.installAccountsForTest(listOf(old))
        store.write(1, old.id, onBackup)

        assertTrue(edit(old, listOf("b2.example:8080", "  ", "http://edited-b1.example")))

        val saved = XtreamRepository.uiState.value.accounts.single()
        assertEquals(listOf("http://b2.example:8080", "http://edited-b1.example"), saved.backupUrls)
        assertEquals(ServerFailoverState(), store.read(1, old.id))
    }

    @Test
    fun `an edit that leaves the server list alone keeps the active backup`() {
        val old = account("renamed")
        XtreamRepository.installAccountsForTest(listOf(old))
        store.write(1, old.id, onBackup)

        assertTrue(edit(old, old.backupUrls, name = "New name"))

        assertEquals("New name", XtreamRepository.uiState.value.accounts.single().name)
        assertEquals(onBackup, store.read(1, old.id))
    }

    @Test
    fun `the add form carries validated backups for xtream and m3u links and stalker`() {
        val base = XtreamFormInput(
            serverUrl = "http://a.example", username = "u", password = "p", name = null,
            epgUrl = null, dnsProvider = "system", autoRefreshHours = 24,
            backupUrls = listOf("B.example", "http://a.example"),
        )
        assertEquals(listOf("http://b.example"), xtreamAccountFromForm(base)?.backupUrls)
        assertEquals(
            listOf("http://cdn.example/list.m3u"),
            m3uAccountFromForm(base.copy(sourceType = SOURCE_TYPE_M3U_URL, m3uUrl = "http://a.example/list.m3u", backupUrls = listOf("cdn.example/list.m3u")))?.backupUrls,
        )
        assertEquals(
            listOf("http://p2.example:8080"),
            stalkerAccountFromForm(base.copy(sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:00:00:01", backupUrls = listOf("p2.example:8080/c/")))?.backupUrls,
        )
    }
}
