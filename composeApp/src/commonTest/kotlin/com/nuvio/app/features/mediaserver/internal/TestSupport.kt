package com.nuvio.app.features.mediaserver.internal

import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.api.MediaServerSyncSink
import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.client.MediaServerHttp
import com.nuvio.app.features.mediaserver.internal.client.MediaServerRequest
import com.nuvio.app.features.mediaserver.internal.client.MediaServerResponse
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserClientIdentity
import com.nuvio.app.features.mediaserver.internal.store.MediaServerCredentialStore
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntriesPersistence
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntryStore
import com.nuvio.app.features.mediaserver.internal.store.SecureTokenStore

/** An in-memory secure store that records everything written, so a test can assert a secret never lands anywhere else. */
internal class FakeSecureTokenStore : SecureTokenStore {
    val items = linkedMapOf<String, String>()
    var failWrites = false
    override fun read(key: String): String? = items[key]
    override fun write(key: String, value: String?) {
        if (failWrites) error("keychain unavailable")
        if (value == null) items.remove(key) else items[key] = value
    }
    override fun clearAll() = items.clear()
}

internal class FakeEntriesPersistence : MediaServerEntriesPersistence {
    val blobs = linkedMapOf<Int, String>()
    var failWrites = false
    var writes = 0
    override fun load(profileId: Int): String? = blobs[profileId]
    override fun save(profileId: Int, json: String) {
        if (failWrites) error("disk full")
        writes++
        blobs[profileId] = json
    }
    override fun remove(profileId: Int) { blobs.remove(profileId) }
}

internal class RecordingSink : MediaServerSyncSink {
    val events = mutableListOf<String>()
    val added = mutableListOf<MediaServerEntry>()
    val updated = mutableListOf<Pair<MediaServerEntry, MediaServerEntry?>>()
    val deleted = mutableListOf<String>()
    override fun recordAdd(profileId: Int, entry: MediaServerEntry) { events += "add:$profileId:${entry.key}"; added += entry }
    override fun recordUpdate(profileId: Int, entry: MediaServerEntry, base: MediaServerEntry?) { events += "update:$profileId:${entry.key}"; updated += entry to base }
    override fun recordDelete(profileId: Int, key: String) { events += "delete:$profileId:$key"; deleted += key }
}

/** Scripted HTTP: [respond] answers every request; [requests] keeps them for assertions. */
internal class FakeHttp(private val respond: (MediaServerRequest) -> MediaServerResponse) : MediaServerHttp {
    val requests = mutableListOf<MediaServerRequest>()
    override suspend fun execute(request: MediaServerRequest): MediaServerResponse {
        requests += request
        return respond(request)
    }
}

internal fun json(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) = MediaServerResponse(status, headers, body)

internal const val M = "6f3c1a9e2b7d4c58a1e0f9d8c7b6a543"
internal const val U = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"

internal fun entry(
    type: MediaServerType = MediaServerType.JELLYFIN, machineId: String = M, userId: String = U, name: String = "Home",
    address: String? = "http://nas:8096",
) = MediaServerEntry(
    key = "${type.wire}|$machineId|$userId", type = type, machineId = machineId, userId = userId, name = name, address = address,
)

internal class TestRig(
    val http: FakeHttp = FakeHttp { error("no HTTP expected: ${it.method} ${it.url}") },
    var nowMs: Long = 1_000_000L,
    activeProfile: Int = 1,
    clientFactory: ((MediaServerEntry) -> com.nuvio.app.features.mediaserver.internal.client.MediaServerClient)? = null,
) {
    val secure = FakeSecureTokenStore()
    val persistence = FakeEntriesPersistence()
    val sink = RecordingSink()
    val migrations = mutableListOf<Pair<String, String?>>()
    var profile = activeProfile
    val credentials = MediaServerCredentialStore(secure)
    val store = MediaServerEntryStore(persistence, { sink }, { profile }, { o, n -> migrations += o to n })
    val services = MediaServerServices(
        http = http,
        credentials = credentials,
        identityProvider = { MediaBrowserClientIdentity("Tuvora", "Test Device", credentials.deviceId(), "1.0.0") },
        nowMs = { nowMs },
        staggerMs = 0L,
        clientFactory = clientFactory,
    )
}

/** A scripted [com.nuvio.app.features.mediaserver.internal.client.MediaServerClient] for the source-lane tests. */
internal class FakeClient : com.nuvio.app.features.mediaserver.internal.client.MediaServerClient {
    var items = mutableMapOf<String, com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto>()
    var episodesOf = mutableMapOf<String, List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto>>()
    var searchHits: List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto> = emptyList()
    var shelves = com.nuvio.app.features.mediaserver.internal.client.HomeShelves()
    var negotiation = com.nuvio.app.features.mediaserver.internal.client.PlaybackNegotiation(emptyList(), null)
    var failWith: com.nuvio.app.features.mediaserver.internal.client.MediaServerException? = null
    /** When set, answers each PlaybackInfo by its request (a server that behaves differently when asked to transcode). */
    var negotiationFor: ((com.nuvio.app.features.mediaserver.internal.client.PlaybackInfoRequest) -> com.nuvio.app.features.mediaserver.internal.client.PlaybackNegotiation)? = null
    val reports = mutableListOf<com.nuvio.app.features.mediaserver.internal.client.PlaybackReport>()
    val played = mutableListOf<Pair<String, Boolean>>()
    val playbackRequests = mutableListOf<Pair<String, com.nuvio.app.features.mediaserver.internal.client.PlaybackInfoRequest>>()
    var homeCalls = 0
    var lastHomeRows: Set<com.nuvio.app.features.mediaserver.api.MediaServerHomeRow> = emptySet()
    val itemRequests = mutableListOf<String>()

    private fun check() { failWith?.let { throw it } }

    override suspend fun me() = com.nuvio.app.features.mediaserver.internal.client.mediabrowser.UserDto(id = "u")
    var viewsList = emptyList<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto>()
    var loggedOut = 0
    val itemQueries = mutableListOf<com.nuvio.app.features.mediaserver.internal.client.ItemsQuery>()
    var authorized = mutableListOf<String>()
    override suspend fun views(): List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto> { check(); return viewsList }
    override suspend fun items(query: com.nuvio.app.features.mediaserver.internal.client.ItemsQuery): com.nuvio.app.features.mediaserver.internal.client.ItemsPage {
        check(); itemQueries += query
        return com.nuvio.app.features.mediaserver.internal.client.ItemsPage(searchHits, searchHits.size, query.startIndex ?: 0)
    }
    override suspend fun item(itemId: String, fields: String?): com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto? {
        check(); itemRequests += itemId
        return items[itemId]
    }
    var seasonsOf = mutableMapOf<String, List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto>>()
    override suspend fun seasons(seriesId: String): List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto> { check(); return seasonsOf[seriesId].orEmpty() }
    override suspend fun episodes(seriesId: String, seasonId: String?, fields: String?): List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto> {
        check()
        return episodesOf[seriesId].orEmpty()
    }
    override suspend fun search(term: String, limit: Int): List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto> {
        check()
        return searchHits
    }
    override suspend fun homeShelves(rows: Set<com.nuvio.app.features.mediaserver.api.MediaServerHomeRow>, limit: Int, fields: String): com.nuvio.app.features.mediaserver.internal.client.HomeShelves {
        homeCalls++; lastHomeRows = rows
        check()
        return shelves
    }
    val lookups = mutableListOf<com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.Query>()
    var lookupAnswer: (com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.Query) -> List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto> = { emptyList() }
    override suspend fun lookup(query: com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.Query, limit: Int): List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto> {
        check(); lookups += query
        return lookupAnswer(query)
    }
    override suspend fun playbackInfo(itemId: String, request: com.nuvio.app.features.mediaserver.internal.client.PlaybackInfoRequest): com.nuvio.app.features.mediaserver.internal.client.PlaybackNegotiation {
        check(); playbackRequests += itemId to request
        return negotiationFor?.invoke(request) ?: negotiation
    }
    override suspend fun report(report: com.nuvio.app.features.mediaserver.internal.client.PlaybackReport) { check(); reports += report }
    override suspend fun setPlayed(itemId: String, played: Boolean) { check(); this.played += itemId to played }
    override suspend fun authorizeQuickConnect(code: String) { check(); authorized += code }
    override suspend fun logout() { check(); loggedOut++ }
}

internal fun item(
    id: String, name: String = id, type: String = "Movie", tags: Map<String, String?> = mapOf("Primary" to "ptag"),
    sources: List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaSourceDto> = emptyList(),
    extra: (com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto) -> com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto = { it },
) = extra(com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto(id = id, name = name, type = type, imageTags = tags, mediaSources = sources))

internal fun source(
    id: String = "src1", container: String = "mkv", protocol: String = "File", direct: Boolean = true, stream: Boolean = true, transcode: Boolean = true,
    transcodingUrl: String? = "/videos/i/master.m3u8?MediaSourceId=src1&ApiKey=SERVER-BUILT", height: Int = 1080, codec: String = "h264", size: Long = 4_500_000_000,
) = com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaSourceDto(
    id = id, container = container, protocol = protocol, size = size, supportsDirectPlay = direct, supportsDirectStream = stream,
    supportsTranscoding = transcode, transcodingUrl = transcodingUrl,
    mediaStreams = listOf(com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaStreamDto(index = 0, type = "Video", codec = codec, height = height)),
)
