package com.nuvio.app.features.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Step 0 — the frozen playlist id on the wire, through an edit, and through a pull. */
class PlaylistKeySyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun reset() {
        XtreamRepository.verifyForTest = null
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
    }

    @Test
    fun `every push row carries playlist_key and round-trips backup_urls`() {
        val acc = XtreamAccount(
            id = "http://old.example|u", name = "P", baseUrl = "http://new.example", username = "u", password = "p",
            backupUrls = listOf("http://b1.example", "http://b2.example"),
        )
        val row = playlistPushPayload(listOf(acc)).single() as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("http://old.example|u"), row["playlist_key"], "the id rides the push, not the address")
        assertEquals(JsonArray(listOf(JsonPrimitive("http://b1.example"), JsonPrimitive("http://b2.example"))), row["backup_urls"])
        val empty = playlistPushPayload(listOf(acc.copy(backupUrls = emptyList()))).single() as kotlinx.serialization.json.JsonObject
        assertEquals(JsonArray(emptyList()), empty["backup_urls"], "an empty list is sent as [] — the pull gave nothing")

        val restored = json.decodeFromJsonElement<PlaylistRow>(row).toAccount()!!
        assertEquals("http://old.example|u", restored.id, "the pulled id is the key, not a re-derivation of the address")
        assertEquals(listOf("http://b1.example", "http://b2.example"), restored.backupUrls)
    }

    @Test
    fun `a pull without playlist_key falls back to the address derivation`() {
        val row = PlaylistRow(sourceType = "xtream", baseUrl = "http://h.example", username = "u", password = "p")
        val pulled = pulledPlaylists(listOf(row)).single()
        assertEquals("http://h.example|u", pulled.account.id)
        assertEquals(false, pulled.serverKeyed)
        assertEquals(emptyList(), pulled.account.backupUrls)
        val keyed = pulledPlaylists(listOf(row.copy(playlistKey = "K"))).single()
        assertEquals("K", keyed.account.id)
        assertEquals(true, keyed.serverKeyed)
    }

    @Test
    fun `an address edit keeps the playlist id`() = runBlocking {
        // THE regression: a provider moving domains re-keyed the playlist, orphaning hidden channels,
        // favourites and progress (all keyed on this id).
        val old = XtreamAccount(
            id = "http://old.example:8080|u", name = "P", baseUrl = "http://old.example:8080", username = "u", password = "p",
            backupUrls = listOf("http://backup.example"),
        )
        XtreamRepository.installAccountsForTest(listOf(old))
        XtreamRepository.persistWriteForTest = { _, _ -> }
        XtreamRepository.verifyForTest = { Result.success(Unit) }

        val done = CompletableDeferred<Boolean>()
        XtreamRepository.editFromForm(
            old.id,
            XtreamFormInput(
                serverUrl = "https://new.example", username = "u2", password = "p2", name = "P",
                epgUrl = null, dnsProvider = "system", autoRefreshHours = 24,
            ),
        ) { done.complete(it) }

        assertTrue(withTimeout(10_000) { done.await() }, "the edit reports saved")
        val saved = XtreamRepository.uiState.value.accounts.single()
        assertEquals(old.id, saved.id, "the id is frozen across a server + username edit")
        assertEquals("https://new.example", saved.baseUrl, "the new address is saved")
        assertEquals("u2", saved.username)
        assertEquals(listOf("http://backup.example"), saved.backupUrls, "the edit form does not touch the backup list")
        val wire = playlistPushPayload(listOf(saved)).single() as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive(old.id), wire["playlist_key"], "and the server is told the same id")
    }

    @Test
    fun `a pull with a mismatched key re-keys once and the next pull is a no-op`() = runBlocking {
        val local = XtreamAccount(id = "m3u:http://h/a.m3u", name = "M", baseUrl = "http://h/a.m3u", username = "", password = "", sourceType = SOURCE_TYPE_M3U_URL)
        val serverRow = local.copy(id = "m3u|http://h/a.m3u")
        var deviceAccounts = listOf(local)
        val rekeys = mutableListOf<PlaylistKeyAdoption.Rekey>()
        var stateJson: String? = null
        val transport = object : PlaylistSyncTransport {
            override suspend fun pull(profileId: Int) = PlaylistPullResponse(3, listOf(serverRow), 0, keyedIds = setOf(serverRow.id))
            override suspend fun push(
                profileId: Int, expectedRevision: Long?, accounts: List<XtreamAccount>, deleteAll: Boolean,
                mutationId: String, expectedGeneration: Long?,
            ): PlaylistPushResponse = error("an adopted, otherwise identical set must not push")
        }
        val engine = PlaylistV2SyncEngine(
            transport = transport,
            loadState = { decodePlaylistSyncState(stateJson) },
            saveState = { _, s -> stateJson = encodePlaylistSyncState(s) },
            currentAccounts = { deviceAccounts },
            canPush = { true },
            applyLocal = { _, accounts -> deviceAccounts = accounts },
            stillActive = { true },
            newMutationId = { "m" },
            adoptKeys = { _, pulled, keyed ->
                val r = PlaylistKeyAdoption.resolve(pulled.map { PulledPlaylist(it, it.id in keyed) }, deviceAccounts)
                rekeys += r.rekeys
                deviceAccounts = deviceAccounts.map { a -> r.rekeys.firstOrNull { it.oldId == a.id }?.let { a.copy(id = it.newId) } ?: a }
                r
            },
        )

        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, engine.sync(1))
        assertEquals(listOf(PlaylistKeyAdoption.Rekey("m3u:http://h/a.m3u", "m3u|http://h/a.m3u")), rekeys)
        assertEquals(listOf("m3u|http://h/a.m3u"), deviceAccounts.map { it.id })

        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, engine.sync(1))
        assertEquals(1, rekeys.size, "the second pull re-keys nothing")
    }
}
