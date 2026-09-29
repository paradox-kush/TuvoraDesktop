package com.nuvio.app.features.iptv.match

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.trakt.TraktPlatformClock
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Syncs verified TMDB->stream mappings with the `iptv_tmdb_map` table, mirroring
 * XtreamAccountSyncService's shape. Rows are per user+provider (profiles share them).
 * Pull is delta-shaped per MatchMapPullPolicy (LWW merge into the local SQLite mirror); push is
 * a debounced upsert of locally-confirmed rows. Anonymous sessions stay device-local.
 */
internal object XtreamMatchSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("XtreamMatchSync")
    private const val PUSH_DEBOUNCE_MS = 2_000L

    private val pulledProviders = mutableSetOf<String>()
    private val pullMutex = Mutex()
    private var pushJob: Job? = null

    @Serializable
    private data class MapRow(
        @SerialName("provider_key") val providerKey: String,
        @SerialName("content_type") val contentType: String,
        @SerialName("tmdb_id") val tmdbId: Int,
        @SerialName("stream_id") val streamId: Int? = null,
        @SerialName("matched_name") val matchedName: String? = null,
        @SerialName("updated_at_ms") val updatedAtMs: Long,
    )

    private fun authed(): Boolean {
        val s = AuthRepository.state.value
        return s is AuthState.Authenticated && !s.isAnonymous
    }

    private fun ownerId(): String? =
        (AuthRepository.state.value as? AuthState.Authenticated)?.takeIf { !it.isAnonymous }?.userId

    private val remote = MatchMapRemote { provider, sinceMs, offset, limit ->
        SupabaseProvider.client.postgrest
            .from("iptv_tmdb_map")
            .select {
                filter {
                    eq("provider_key", provider)
                    if (sinceMs != null) gte("updated_at_ms", sinceMs)
                }
                // Ascending is load-bearing: a pull interrupted between pages leaves the mark at
                // the newest APPLIED row, and the next delta resumes from there.
                order("updated_at_ms", Order.ASCENDING)
                order("content_type", Order.ASCENDING)
                order("tmdb_id", Order.ASCENDING)
                range(offset.toLong(), (offset + limit - 1).toLong())
            }
            .decodeList<MapRow>()
            .mapNotNull { row ->
                val kind = MatchKind.entries.firstOrNull { it.slug == row.contentType } ?: return@mapNotNull null
                RemoteMapping(kind, row.tmdbId, row.streamId, row.matchedName, row.updatedAtMs)
            }
    }

    private val store = object : MatchMapStore {
        override suspend fun readCursor(owner: String, provider: String) = XtreamMatchIndex.readPullCursor(owner, provider)
        override suspend fun applyPage(owner: String, provider: String, rows: List<RemoteMapping>, cursor: MatchMapCursor) =
            XtreamMatchIndex.applyPulledPage(owner, provider, rows, cursor)
    }

    /**
     * Merge this provider's remote mappings into the local mirror. At most once per provider per
     * session, and even then delta-shaped (B78): [MatchMapPullPolicy] fetches only rows newer than
     * the persisted cursor, or nothing at all when the last pull was recent.
     */
    suspend fun pullOnce(provider: String) {
        val owner = ownerId() ?: return
        pullMutex.withLock { if (!pulledProviders.add(provider)) return }
        runCatching {
            val r = MatchMapPuller.pull(owner, provider, TraktPlatformClock.nowEpochMs(), store, remote)
            log.i { "pullOnce($provider) — full=${r.full} requests=${r.requests} rows=${r.fetched} applied=${r.applied}" }
        }.onFailure { e ->
            pullMutex.withLock { pulledProviders.remove(provider) } // retry next resolve
            log.w(e) { "pullOnce($provider) — FAILED" }
        }
    }

    /** Debounced push of not-yet-synced local mappings for this provider. */
    fun triggerPush(provider: String) {
        if (!authed()) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            runCatching {
                val pending = XtreamMatchIndex.unsyncedMappings(provider)
                if (pending.isEmpty()) return@runCatching
                val rows = pending.map {
                    MapRow(
                        providerKey = provider,
                        contentType = it.kind,
                        tmdbId = it.tmdb,
                        streamId = it.sid,
                        matchedName = it.matchedName,
                        updatedAtMs = it.updatedAtMs,
                    )
                }
                SupabaseProvider.client.postgrest.from("iptv_tmdb_map").upsert(rows)
                for (row in pending) XtreamMatchIndex.markSynced(provider, row.kind, row.tmdb)
                log.d { "pushed ${rows.size} mappings for $provider" }
            }.onFailure { e -> log.w(e) { "push($provider) — FAILED" } }
        }
    }

    /** Call on profile switch/logout so the next session re-pulls. */
    fun reset() {
        pushJob?.cancel()
        pushJob = null
        scope.launch { pullMutex.withLock { pulledProviders.clear() } }
    }
}
