package com.nuvio.app.features.iptv

import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.api.MediaServerPendingOp
import com.nuvio.app.features.mediaserver.api.MediaServerPendingOps.recordAdd
import com.nuvio.app.features.mediaserver.api.MediaServerPendingOps.recordDelete
import com.nuvio.app.features.mediaserver.api.MediaServerPendingOps.recordUpdate
import com.nuvio.app.features.mediaserver.api.MediaServerSyncBinding
import com.nuvio.app.features.mediaserver.api.MediaServerType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 3: Jellyfin/Emby server entries ride the ONE playlist-sync engine (design 5.3) - a second row mapper and
 * a second pending log under the same revision counter, never a second sync loop. The engine is driven through
 * a fake transport + in-memory state, exactly like PlaylistV2SyncEngineTest.
 */
class PlaylistMediaServerSyncTest {
    private fun acc(id: String) = XtreamAccount(id = id, name = "P", baseUrl = "http://$id", username = "u", password = "p")
    private fun ms(n: String, name: String = "Server $n", enabled: Boolean = true, address: String? = "http://$n:8096") = MediaServerEntry(
        key = "jellyfin|m$n|u$n", type = MediaServerType.JELLYFIN, machineId = "m$n", userId = "u$n", name = name, address = address, enabled = enabled,
    )

    private class Server(var accounts: List<XtreamAccount> = emptyList(), var media: List<MediaServerEntry> = emptyList(), var revision: Long = 0) {
        var pushes = 0
        var lastScope: String? = null
        var lastMediaPushed: List<MediaServerEntry>? = null
        var lastDeleteAll: Boolean? = null
        var beforeNextPush: (() -> Unit)? = null
    }

    private class Device(val server: Server, var local: List<XtreamAccount> = emptyList(), var localMedia: List<MediaServerEntry> = emptyList(), var mediaAuthoritative: Boolean = true) {
        val stateStore = HashMap<Int, String>()
        var mutations = 0
        val transport = object : PlaylistSyncTransport {
            override suspend fun pull(profileId: Int) = PlaylistPullResponse(server.revision, server.accounts, 0, emptySet(), server.media)
            override suspend fun push(profileId: Int, expectedRevision: Long?, accounts: List<XtreamAccount>, deleteAll: Boolean, mutationId: String, expectedGeneration: Long?): PlaylistPushResponse =
                error("a media-server-aware engine must push through pushWithMediaServers")
            override suspend fun pushWithMediaServers(
                profileId: Int, expectedRevision: Long?, accounts: List<XtreamAccount>, mediaServers: List<MediaServerEntry>,
                deleteAll: Boolean, mutationId: String, expectedGeneration: Long?,
            ): PlaylistPushResponse {
                server.beforeNextPush?.let { server.beforeNextPush = null; it() }
                server.pushes++
                server.lastMediaPushed = mediaServers
                server.lastDeleteAll = deleteAll
                if (accounts.isEmpty() && mediaServers.isEmpty() && !deleteAll) return PlaylistPushResponse.Rejected("empty without delete_all")
                if (expectedRevision != null && expectedRevision != server.revision) return PlaylistPushResponse.Conflict(server.revision, server.accounts, emptySet(), server.media)
                server.accounts = accounts; server.media = mediaServers; server.revision += 1
                return PlaylistPushResponse.Ok(server.revision)
            }
        }
        val binding = MediaServerSyncBinding(
            currentEntries = { localMedia },
            canPushFullReplace = { mediaAuthoritative },
            applyFromRemote = { _, remote -> localMedia = com.nuvio.app.features.mediaserver.api.MediaServerPendingOps.applyRemote(remote, localMedia); mediaAuthoritative = true },
        )

        fun engine() = PlaylistV2SyncEngine(
            transport = transport,
            loadState = { decodePlaylistSyncState(stateStore[it]) },
            saveState = { p, s -> stateStore[p] = encodePlaylistSyncState(s) },
            currentAccounts = { local },
            canPush = { true },
            applyLocal = { _, accts -> local = accts },
            stillActive = { true },
            newMutationId = { "mut-${++mutations}" },
            mediaServers = binding,
        )

        fun state() = decodePlaylistSyncState(stateStore[1])
        private fun mutate(f: (List<MediaServerPendingOp>) -> List<MediaServerPendingOp>) {
            stateStore[1] = encodePlaylistSyncState(state().let { it.copy(mediaServerPending = f(it.mediaServerPending)) })
        }
        fun add(e: MediaServerEntry) { localMedia = localMedia + e; mutate { it.recordAdd(e) } }
        fun update(e: MediaServerEntry, base: MediaServerEntry) { localMedia = localMedia.map { if (it.key == e.key) e else it }; mutate { it.recordUpdate(e, base) } }
        fun delete(key: String) { localMedia = localMedia.filterNot { it.key == key }; mutate { it.recordDelete(key) } }
    }

    @Test
    fun aLocalServerEntryIsPushedAndItsPendingIntentAcknowledged() = runBlocking {
        val server = Server()
        val a = Device(server)
        a.add(ms("1"))
        assertEquals(PlaylistSyncOutcome.SYNCED, a.engine().sync(1))
        assertEquals(listOf("jellyfin|m1|u1"), server.media.map { it.key })
        assertEquals(1L, server.revision)
        assertTrue(a.state().mediaServerPending.isEmpty(), "acknowledged only after the commit")
        assertFalse(server.lastDeleteAll!!)
    }

    @Test
    fun aSecondDeviceAdoptsTheEntryAsSignInToWithoutPushing() = runBlocking {
        val server = Server(media = listOf(ms("1", address = null)), revision = 3)
        val b = Device(server)
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, b.engine().sync(1))
        assertEquals(listOf("jellyfin|m1|u1"), b.localMedia.map { it.key })
        assertNull(b.localMedia.single().address, "no address synced: the device types its own")
        assertEquals(0, server.pushes, "adopting a server's state never pushes")
        // and the second sync is a no-op
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, b.engine().sync(1))
        assertEquals(0, server.pushes)
    }

    @Test
    fun aRemovalOnOneDevicePropagatesToTheOther() = runBlocking {
        val server = Server(media = listOf(ms("1"), ms("2")), revision = 5)
        val a = Device(server, localMedia = server.media)
        val b = Device(server, localMedia = server.media)
        a.delete("jellyfin|m1|u1")
        assertEquals(PlaylistSyncOutcome.SYNCED, a.engine().sync(1))
        assertEquals(listOf("jellyfin|m2|u2"), server.media.map { it.key })
        b.engine().sync(1)
        assertEquals(listOf("jellyfin|m2|u2"), b.localMedia.map { it.key }, "no resurrection from the stale device")
    }

    @Test
    fun aConflictReReconcilesTheSameIntentOntoTheNewerServerRows() = runBlocking {
        val server = Server(media = listOf(ms("1")), revision = 2)
        val a = Device(server, localMedia = listOf(ms("1")))
        a.add(ms("3"))
        // another device adds server 2 between our pull and our push
        server.beforeNextPush = { server.media = server.media + ms("2"); server.revision += 1 }
        assertEquals(PlaylistSyncOutcome.SYNCED, a.engine().sync(1))
        assertEquals(setOf("jellyfin|m1|u1", "jellyfin|m2|u2", "jellyfin|m3|u3"), server.media.map { it.key }.toSet(), "neither device's add is lost")
        assertEquals(setOf("jellyfin|m1|u1", "jellyfin|m2|u2", "jellyfin|m3|u3"), a.localMedia.map { it.key }.toSet())
    }

    @Test
    fun aFieldEditOnlyOverridesTheFieldsThisDeviceChanged() = runBlocking {
        val original = ms("1")
        val server = Server(media = listOf(original.copy(name = "Renamed on another device")), revision = 4)
        val a = Device(server, localMedia = listOf(original))
        a.update(original.copy(enabled = false), base = original)
        a.engine().sync(1)
        val stored = server.media.single()
        assertEquals("Renamed on another device", stored.name)
        assertFalse(stored.enabled)
    }

    @Test
    fun aDamagedLocalStoreNeverDeletesTheServersEntries() = runBlocking {
        val server = Server(accounts = listOf(acc("A")), media = listOf(ms("1"), ms("2")), revision = 7)
        val a = Device(server, local = listOf(acc("A")), localMedia = emptyList(), mediaAuthoritative = false)
        a.engine().sync(1)
        assertEquals(2, server.media.size, "a local store this build could not read must never full-replace the server's rows")
        assertEquals(2, a.localMedia.size, "the pull heals it")
        // even when the Xtream side has a pending edit and pushes
        val b = Device(server, local = listOf(acc("A")), localMedia = emptyList(), mediaAuthoritative = false)
        b.stateStore[1] = encodePlaylistSyncState(PlaylistSyncState(pending = emptyList<PendingOpDto>().recordAdd(acc("B"))))
        b.local = listOf(acc("A"), acc("B"))
        b.engine().sync(1)
        assertEquals(listOf("A", "B"), server.accounts.map { it.id }.sorted())
        assertEquals(2, server.media.size, "the server entries ride along unchanged")
    }

    @Test
    fun theFirstEverV2WritePushesLocalEntriesAsAnInitialCreation() = runBlocking {
        val server = Server()
        val a = Device(server, localMedia = listOf(ms("1")))
        assertEquals(PlaylistSyncOutcome.SYNCED, a.engine().sync(1))
        assertEquals(1, server.media.size)
        val offline = Server()
        val b = Device(offline, localMedia = listOf(ms("1")), mediaAuthoritative = false)
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, b.engine().sync(1), "an absent store is not an authored set")
        assertEquals(0, offline.pushes)
    }

    @Test
    fun deletingTheLastEntryOfAProfileWithNoPlaylistsStillPushesAnExplicitDeleteAll() = runBlocking {
        val server = Server(media = listOf(ms("1")), revision = 2)
        val a = Device(server, localMedia = listOf(ms("1")))
        a.delete("jellyfin|m1|u1")
        a.engine().sync(1)
        assertTrue(server.media.isEmpty())
        assertTrue(server.lastDeleteAll!!, "an empty payload requires the explicit intent")
    }

    @Test
    fun anEngineWithoutTheMediaServerBindingBehavesExactlyAsBefore() = runBlocking {
        var local = listOf(acc("A"))
        val s = Server(accounts = listOf(acc("A")), media = listOf(ms("1")), revision = 3)
        var pushed = 0
        val transport = object : PlaylistSyncTransport {
            override suspend fun pull(profileId: Int) = PlaylistPullResponse(s.revision, s.accounts, 0, emptySet(), s.media)
            override suspend fun push(profileId: Int, expectedRevision: Long?, accounts: List<XtreamAccount>, deleteAll: Boolean, mutationId: String, expectedGeneration: Long?): PlaylistPushResponse { pushed++; return PlaylistPushResponse.Ok(4) }
        }
        val engine = PlaylistV2SyncEngine(transport, { decodePlaylistSyncState(null) }, { _, _ -> }, { local }, { true }, { _, a -> local = a }, { true }, { "m" })
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, engine.sync(1))
        assertEquals(0, pushed)
    }

    @Test
    fun theMutationIdFingerprintIsUnchangedWithoutServerEntriesAndDiffersWithThem() {
        val accounts = listOf(acc("A"))
        assertEquals(PlaylistMutationIdPolicy.fingerprint(accounts, false), PlaylistMutationIdPolicy.fingerprint(accounts, false, emptyList()))
        assertTrue(PlaylistMutationIdPolicy.fingerprint(accounts, false) != PlaylistMutationIdPolicy.fingerprint(accounts, false, listOf(ms("1"))))
        assertTrue(PlaylistMutationIdPolicy.fingerprint(accounts, false, listOf(ms("1"))) != PlaylistMutationIdPolicy.fingerprint(accounts, false, listOf(ms("1", name = "x"))))
    }

    // --- the row mapper and the wire ---

    private fun row(vararg pairs: Pair<String, Any?>): PlaylistRow {
        val obj = kotlinx.serialization.json.buildJsonObject {
            pairs.forEach { (k, v) ->
                when (v) {
                    null -> {}
                    is Boolean -> put(k, JsonPrimitive(v))
                    is Number -> put(k, JsonPrimitive(v))
                    else -> put(k, JsonPrimitive(v.toString()))
                }
            }
        }
        return kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromJsonElement(PlaylistRow.serializer(), obj)
    }

    @Test
    fun theTwoRowMappersPartitionThePulledTable() {
        val rows = listOf(
            row("source_type" to "xtream", "base_url" to "http://x:80", "username" to "u", "password" to "p"),
            row("source_type" to "jellyfin", "playlist_key" to "jellyfin|mA|uA", "name" to "Home", "url" to "jellyfin://mA", "username" to "uA", "base_url" to "http://nas:8096"),
            row("source_type" to "emby", "playlist_key" to "emby|mB|uB", "url" to "emby://mB", "username" to "uB"),
            row("source_type" to "plex", "playlist_key" to "plex|mC|uC", "url" to "plex://mC", "username" to "uC"),
        )
        assertEquals(listOf("jellyfin|mA|uA", "emby|mB|uB"), mediaServerEntries(rows).map { it.key })
        assertEquals(1, usableRemoteAccounts(rows).size, "the playlist mapper drops server rows (and parked Plex)")
        assertEquals("Home", mediaServerEntries(rows).first().name)
    }

    @Test
    fun thePushPayloadCarriesServerEntriesWithoutAnyCredential() {
        val payload: JsonArray = playlistPushPayload(listOf(acc("A")), listOf(ms("1"), ms("2", address = null)))
        assertEquals(3, payload.size)
        val server = payload[1].jsonObject
        assertEquals("jellyfin", server.getValue("source_type").jsonPrimitive.content)
        assertEquals("jellyfin|m1|u1", server.getValue("playlist_key").jsonPrimitive.content)
        assertEquals(1, server.getValue("sort_order").jsonPrimitive.content.toInt(), "positions continue after the playlists")
        assertFalse("base_url" in payload[2].jsonObject.keys, "an unsynced address is simply absent")
        val forbidden = setOf("password", "stalker_password", "mac_address", "device_id", "serial_number", "signature")
        payload.drop(1).forEach { r -> assertTrue(r.jsonObject.keys.none { it in forbidden }, "credential-free: ${r.jsonObject.keys}") }
    }

    @Test
    fun theFullReplaceScopeNamesServerTypesOnlyOnTheV2PathNeverTheLegacyOne() {
        assertTrue("jellyfin" in V2_SYNCED_SOURCE_TYPES && "emby" in V2_SYNCED_SOURCE_TYPES)
        assertTrue(SYNCED_SOURCE_TYPES.none { it == "jellyfin" || it == "emby" }, "the legacy v1 push carries no server rows: naming them in its scope would delete them")
        val legacy = playlistPushParams(1, listOf(acc("A")))
        assertEquals(SYNCED_SOURCE_TYPES, legacy.getValue("p_source_types").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(V2_SYNCED_SOURCE_TYPES.containsAll(SYNCED_SOURCE_TYPES))
        assertFalse("plex" in V2_SYNCED_SOURCE_TYPES, "Plex is parked: its rows are never touched")
    }

    @Test
    fun syncStateWrittenByAnOlderBuildStillDecodes() {
        val old = """{"revision":4,"mutationId":"m-1","pending":[],"deleteAllIntent":false,"generation":2}"""
        val s = decodePlaylistSyncState(old)
        assertEquals(4L, s.revision)
        assertTrue(s.mediaServerPending.isEmpty())
        val roundTrip = decodePlaylistSyncState(encodePlaylistSyncState(s.copy(mediaServerPending = listOf(MediaServerPendingOp("delete", "jellyfin|m|u")))))
        assertEquals(1, roundTrip.mediaServerPending.size)
    }

    @Test
    fun theSharedKeyBuilderDelegatesToTheFeature() {
        assertEquals("jellyfin|abc|def", PlaylistKey.mediaServer("jellyfin", "abc", "def"))
        assertNull(PlaylistKey.mediaServer("jellyfin", "http://x", "def"))
    }
}
