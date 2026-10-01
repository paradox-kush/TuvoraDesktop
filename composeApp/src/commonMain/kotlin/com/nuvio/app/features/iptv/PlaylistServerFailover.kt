package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.HttpStatusException
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Step 0.3 port — where a playlist's [ServerFailoverState] lives, per (profile, playlist key).
 * Device-local, never synced. Production: [PrefsServerFailoverStateStore]; tests use an in-memory one.
 */
interface ServerFailoverStateStore {
    fun read(profileId: Int, playlistKey: String): ServerFailoverState
    fun write(profileId: Int, playlistKey: String, state: ServerFailoverState)
    fun clear(profileId: Int, playlistKey: String)
    /** Every non-default state of [profileId], keyed by playlist key (drives the "Using backup" row). */
    fun all(profileId: Int): Map<String, ServerFailoverState>
}

/** In-memory [ServerFailoverStateStore] — the test double, and the shape the prefs store caches. */
class InMemoryServerFailoverStateStore : ServerFailoverStateStore {
    private val states = MutableStateFlow<Map<Int, Map<String, ServerFailoverState>>>(emptyMap())
    override fun read(profileId: Int, playlistKey: String) =
        states.value[profileId]?.get(playlistKey) ?: ServerFailoverState()
    override fun write(profileId: Int, playlistKey: String, state: ServerFailoverState) = states.update { all ->
        val profile = all[profileId].orEmpty()
        all + (profileId to if (state == ServerFailoverState()) profile - playlistKey else profile + (playlistKey to state))
    }
    override fun clear(profileId: Int, playlistKey: String) = write(profileId, playlistKey, ServerFailoverState())
    override fun all(profileId: Int): Map<String, ServerFailoverState> = states.value[profileId].orEmpty()
}

/**
 * The production store: one JSON map per profile in the IPTV prefs bag
 * ([XtreamAccountStorage.loadServerFailoverJson]), cached in memory so building a stream URL never
 * touches disk. Only non-default states are kept, so a playlist on its main server costs nothing.
 */
internal object PrefsServerFailoverStateStore : ServerFailoverStateStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), ServerFailoverState.serializer())
    private val cache = MutableStateFlow<Map<Int, Map<String, ServerFailoverState>>>(emptyMap())

    private fun profile(profileId: Int): Map<String, ServerFailoverState> {
        cache.value[profileId]?.let { return it }
        val loaded = runCatching {
            XtreamAccountStorage.loadServerFailoverJson(profileId)?.let { json.decodeFromString(serializer, it) }
        }.getOrNull().orEmpty()
        cache.update { it + (profileId to loaded) }
        return loaded
    }

    override fun read(profileId: Int, playlistKey: String) = profile(profileId)[playlistKey] ?: ServerFailoverState()

    override fun write(profileId: Int, playlistKey: String, state: ServerFailoverState) {
        val current = profile(profileId)
        val next = if (state == ServerFailoverState()) current - playlistKey else current + (playlistKey to state)
        if (next == current) return
        cache.update { it + (profileId to next) }
        runCatching { XtreamAccountStorage.saveServerFailoverJson(profileId, json.encodeToString(serializer, next)) }
    }

    override fun clear(profileId: Int, playlistKey: String) = write(profileId, playlistKey, ServerFailoverState())

    override fun all(profileId: Int): Map<String, ServerFailoverState> = profile(profileId)
}

/**
 * Step 0.3 — THE seam every fail-over-able IPTV request goes through, and the one place that knows a
 * playlist's ACTIVE server.
 *
 * FAILS OVER (via [run]): the Xtream login/account call and catalog calls (categories, streams,
 * series, series/vod info, short EPG, the EPG table, derived xmltv.php), the M3U-link playlist
 * download, and Stalker handshake/profile/browse calls. NEVER fails over: stream/playback URLs,
 * Stalker create_link, catch-up/timeshift URLs — those are built on [activeAccount]'s server (or
 * moved there by [rebaseStreamUrl]) and their failures never touch this state.
 *
 * Decisions are [ServerFailoverPolicy] (order/state) and [FailoverFailureClassifier] (which error moves
 * on); this object only executes them. A playlist with no backups takes the original single-request
 * path untouched — no state is read or written.
 */
object PlaylistServerFailover {

    /** The existing per-request timeout of every platform transport (connect/read 60 s). */
    const val SINGLE_REQUEST_TIMEOUT_MS: Long = 60_000L

    internal var store: ServerFailoverStateStore = PrefsServerFailoverStateStore
    internal var clock: () -> Long = { TraktPlatformClock.nowEpochMs() }
    internal var profileId: () -> Int = { ProfileRepository.activeProfileId }

    private val _version = MutableStateFlow(0L)
    /** Bumps on every state change, so state holders can re-read [activeIndexes]. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** Main first, then the backups in priority order. An M3U file has no servers to walk. */
    fun servers(acc: XtreamAccount): List<String> =
        if (BackupServerValidation.supportsBackups(acc.sourceType)) listOf(acc.baseUrl) + acc.backupUrls
        else listOf(acc.baseUrl)

    /** 0 = main; i = backup i. Always a valid index for [acc]'s current list. */
    fun activeIndex(acc: XtreamAccount): Int {
        val servers = servers(acc)
        if (servers.size <= 1) return 0
        return ServerFailoverPolicy.clamp(store.read(profileId(), acc.id), servers.size).activeIndex
    }

    /** [acc] as seen on its active server — what stream/catch-up/create_link URLs are built from. */
    fun activeAccount(acc: XtreamAccount): XtreamAccount {
        val index = activeIndex(acc)
        return if (index == 0) acc else acc.copy(baseUrl = servers(acc)[index])
    }

    /** Active index per playlist key for [accounts], only for those NOT on their main server. */
    fun activeIndexes(accounts: List<XtreamAccount>): Map<String, Int> =
        accounts.mapNotNull { acc -> activeIndex(acc).takeIf { it > 0 }?.let { acc.id to it } }.toMap()

    /**
     * Moves an Xtream stream URL built on any of [acc]'s servers onto the active one (a catalog fetched
     * earlier may have embedded the server that answered then). Anything else is returned unchanged:
     * M3U stream URLs are whatever the playlist lines say, and Stalker resolves create_link fresh.
     */
    fun rebaseStreamUrl(acc: XtreamAccount, url: String): String {
        if (acc.sourceType != SOURCE_TYPE_XTREAM || acc.backupUrls.isEmpty()) return url
        val servers = servers(acc).map { it.trimEnd('/') }
        val active = servers[activeIndex(acc)]
        val from = servers.firstOrNull { url.startsWith("$it/") } ?: return url
        return if (from == active) url else active + url.substring(from.length)
    }

    /**
     * Runs one fail-over-able request: [attempt] is called with [acc] re-pointed at each server in the
     * policy's order until one answers. A failure the classifier says a backup would repeat (401/403,
     * 456, auth=0, cancellation, …) is thrown at once. When every server fails, the MAIN server's error
     * is thrown (the user's primary) and the state is kept.
     *
     * [canRetry] is asked before moving on: a streamed body that already delivered rows to its sink
     * must not be replayed from another server (it would duplicate or splice the catalog).
     */
    suspend fun <T> run(
        acc: XtreamAccount,
        canRetry: () -> Boolean = { true },
        attempt: suspend (XtreamAccount) -> T,
    ): T {
        val servers = servers(acc)
        if (servers.size <= 1) return attempt(acc)
        val pid = profileId()
        val startMs = clock()
        val order = ServerFailoverPolicy.order(store.read(pid, acc.id), servers.size, startMs)
        val budgetMs = ServerFailoverPolicy.walkBudgetMs(SINGLE_REQUEST_TIMEOUT_MS, servers.size)
        var mainError: Throwable? = null
        var lastError: Throwable? = null
        for ((position, index) in order.withIndex()) {
            if (position > 0) {
                if (!canRetry()) throw lastError!!
                if (!ServerFailoverPolicy.mayStartNextAttempt(clock() - startMs, budgetMs)) break
            }
            val result = try {
                attempt(if (index == 0) acc else acc.copy(baseUrl = servers[index]))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (!FailoverFailureClassifier.shouldFailOver(classifyFailoverThrowable(t))) throw t
                if (index == 0) mainError = t
                lastError = t
                continue
            }
            record(pid, acc.id, servers.size) { ServerFailoverPolicy.onSuccess(it, index, clock(), servers.size) }
            return result
        }
        throw mainError ?: lastError ?: IllegalStateException("No server answered for ${acc.name}")
    }

    /** The user edited [playlistKey]'s server list: back to the main server, no window. */
    fun reset(playlistKey: String) {
        record(profileId(), playlistKey, Int.MAX_VALUE) { ServerFailoverState() }
    }

    /** PlaylistRemovalCleanup's ServerFailover target: drop [playlistKey]'s state for [profileId]. */
    fun forget(profileId: Int, playlistKey: String) {
        if (store.read(profileId, playlistKey) == ServerFailoverState()) return
        store.clear(profileId, playlistKey)
        _version.update { it + 1 }
    }

    private fun record(pid: Int, key: String, serverCount: Int, next: (ServerFailoverState) -> ServerFailoverState) {
        val current = store.read(pid, key)
        val updated = next(ServerFailoverPolicy.clamp(current, serverCount))
        if (updated == current) return
        store.write(pid, key, updated)
        _version.update { it + 1 }
    }

    /** Test seam: a fresh in-memory store + clock + profile. Never called from production code. */
    internal fun installForTest(
        store: ServerFailoverStateStore = InMemoryServerFailoverStateStore(),
        clock: () -> Long = { 0L },
        profileId: () -> Int = { 1 },
    ) {
        this.store = store
        this.clock = clock
        this.profileId = profileId
    }

    /** Test seam: back to the production wiring. */
    internal fun resetForTest() {
        store = PrefsServerFailoverStateStore
        clock = { TraktPlatformClock.nowEpochMs() }
        profileId = { ProfileRepository.activeProfileId }
    }
}

/**
 * One throwable from a fail-over-able request, in [FailoverFailureKind] terms. The platform-free
 * cases are decided here; network exceptions are mapped by each platform ([platformFailoverFailureKind]),
 * walking the cause chain because stacks wrap them.
 */
internal fun classifyFailoverThrowable(t: Throwable): FailoverFailure {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 6) {
        when (cur) {
            is CancellationException -> return FailoverFailure(FailoverFailureKind.CANCELLED)
            is HttpStatusException -> return FailoverFailure(FailoverFailureKind.HTTP_STATUS, cur.status)
            is PanelHostFastFailException -> return FailoverFailure(FailoverFailureKind.HOST_UNAVAILABLE)
        }
        platformFailoverFailureKind(cur)?.let { return FailoverFailure(it) }
        cur = cur.cause?.takeIf { it !== cur }
        depth++
    }
    return FailoverFailure(FailoverFailureKind.OTHER)
}

/**
 * The platform's network exception -> [FailoverFailureKind], or null when [t] is not one this platform
 * recognises (the caller then walks the cause chain, ending at OTHER).
 */
internal expect fun platformFailoverFailureKind(t: Throwable): FailoverFailureKind?
