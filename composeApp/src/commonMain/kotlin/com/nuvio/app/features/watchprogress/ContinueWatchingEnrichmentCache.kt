package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.tracking.WatchProgressSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val released: String? = null,
    val hasAired: Boolean = true,
    val lastWatched: Long,
    val sortTimestamp: Long,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
)

@Serializable
data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float? = null,
    val progressKey: String? = null,
)

internal fun CachedInProgressItem.resolvedProgressKey(): String =
    progressKey?.takeIf(String::isNotBlank)
        ?: buildWatchProgressKey(
            contentId = contentId,
            seasonNumber = season,
            episodeNumber = episode,
        )

@Serializable
internal data class CachedEnrichmentPayload(
    val nextUp: List<CachedNextUpItem> = emptyList(),
    val inProgress: List<CachedInProgressItem> = emptyList(),
)

internal object ContinueWatchingEnrichmentCache {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val storageKey = "cw_enrichment_cache"
    // Continue-watching is a bounded UI list. The payload store here is per-app preferences
    // (SharedPreferences / NSUserDefaults / desktop file) — NOT inherently size-bounded, so cap both
    // the producer (what we write) and the consumer (what we decode back), the same 500-record bound
    // the TV file cache uses. This keeps a runaway producer or an old/oversized stored value from
    // materializing an unbounded list on a startup path.
    private const val MAX_RECORDS = 500
    // Byte/char guard enforced BEFORE decoding. The preferences API hands us the stored value as a
    // fully-materialized String (unavoidable at that layer), but decoding it into the object graph is
    // the larger, multiplied cost — so an over-cap value is dropped without ever being decoded, rather
    // than decoding an unbounded graph and only then trimming with capped(). Mirrors the TV file
    // cache's MAX_CACHE_BYTES pre-read check; sized well above a real ~500-record payload.
    private const val MAX_CACHE_CHARS = 4 * 1024 * 1024
    // Always a fresh list: the memoised payload must not alias the caller's (possibly mutable) input.
    private fun <T> List<T>.capped(): List<T> = if (size > MAX_RECORDS) take(MAX_RECORDS) else toList()
    private val cacheLock = SynchronizedObject()
    private val cachedPayloads = mutableMapOf<CacheScope, CachedEnrichmentPayload?>()
    private val migratedLegacyProfileIds = mutableSetOf<Int>()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun warm(profileId: Int) {
        WatchProgressSource.entries.forEach { source ->
            loadPayload(profileId = profileId, source = source)
        }
    }

    fun getNextUpSnapshot(
        profileId: Int,
        source: WatchProgressSource,
    ): List<CachedNextUpItem> =
        loadPayload(profileId = profileId, source = source)?.nextUp ?: emptyList()

    fun getInProgressSnapshot(
        profileId: Int,
        source: WatchProgressSource,
    ): List<CachedInProgressItem> =
        loadPayload(profileId = profileId, source = source)?.inProgress ?: emptyList()

    fun getSnapshots(
        profileId: Int,
        source: WatchProgressSource,
    ): Pair<List<CachedNextUpItem>, List<CachedInProgressItem>> {
        val payload = loadPayload(profileId = profileId, source = source)
        val nextUp = payload?.nextUp ?: emptyList()
        val inProgress = payload?.inProgress ?: emptyList()
        return nextUp to inProgress
    }

    fun saveSnapshots(
        profileId: Int,
        source: WatchProgressSource,
        generation: Int,
        nextUp: List<CachedNextUpItem>,
        inProgress: List<CachedInProgressItem>,
        force: Boolean = false,
    ): Boolean = synchronized(cacheLock) {
        if (generation != _generation.value) return@synchronized false

        // Fork bound (producer cap) + upstream in-memory payload cache: the cached value is the
        // capped payload, so keeping it resident is bounded by MAX_RECORDS.
        val payload = CachedEnrichmentPayload(nextUp = nextUp.capped(), inProgress = inProgress.capped())
        val scope = CacheScope(profileId = profileId, source = source)
        if (!force && cachedPayloads[scope] == payload) {
            return@synchronized true
        }

        removeLegacyPayloadOnce(profileId)
        val encoded = runCatching {
            json.encodeToString(payload)
        }.getOrNull() ?: return@synchronized false
        ContinueWatchingEnrichmentStorage.savePayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            encoded,
        )
        cachedPayloads[scope] = payload
        true
    }

    fun invalidate(
        profileId: Int,
        source: WatchProgressSource,
    ) = synchronized(cacheLock) {
        ContinueWatchingEnrichmentStorage.removePayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
        )
        removeLegacyPayloadOnce(profileId)
        cachedPayloads.remove(CacheScope(profileId = profileId, source = source))
        advanceGeneration()
    }

    fun clearAll(profileId: Int) = synchronized(cacheLock) {
        WatchProgressSource.entries.forEach { source ->
            ContinueWatchingEnrichmentStorage.removePayload(
                continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            )
            cachedPayloads.remove(CacheScope(profileId = profileId, source = source))
        }
        removeLegacyPayloadOnce(profileId)
        advanceGeneration()
    }

    fun clearLocalState() = synchronized(cacheLock) {
        cachedPayloads.clear()
        migratedLegacyProfileIds.clear()
        advanceGeneration()
    }

    fun onProfileChanged() = synchronized(cacheLock) {
        cachedPayloads.clear()
        migratedLegacyProfileIds.clear()
        advanceGeneration()
    }

    private fun loadPayload(
        profileId: Int,
        source: WatchProgressSource,
    ): CachedEnrichmentPayload? = synchronized(cacheLock) {
        val scope = CacheScope(profileId = profileId, source = source)
        if (cachedPayloads.containsKey(scope)) return@synchronized cachedPayloads[scope]
        removeLegacyPayloadOnce(profileId)
        val raw = ContinueWatchingEnrichmentStorage.loadPayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
        ) ?: run {
            cachedPayloads[scope] = null
            return@synchronized null
        }
        val payload = decodeBounded(raw) ?: run {
            // Oversized (byte cap, before decode) or unparseable -> drop the disposable value.
            cachedPayloads[scope] = null
            ContinueWatchingEnrichmentStorage.removePayload(
                continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            )
            return@synchronized null
        }
        cachedPayloads[scope] = payload
        payload
    }

    /**
     * Decode a stored value under BOTH bounds. The byte/char cap is checked FIRST, so an over-cap
     * value is rejected WITHOUT ever decoding it into the object graph — the producer/consumer record
     * caps run post-decode and cannot bound that allocation. A decoded payload's lists are then
     * record-capped. null = drop (oversized or unparseable). Extracted pure so both bounds are
     * unit-testable without the platform preferences store.
     */
    internal fun decodeBounded(raw: String): CachedEnrichmentPayload? {
        if (raw.length > MAX_CACHE_CHARS) return null
        return runCatching { json.decodeFromString<CachedEnrichmentPayload>(raw) }.getOrNull()
            ?.let { CachedEnrichmentPayload(nextUp = it.nextUp.capped(), inProgress = it.inProgress.capped()) }
    }

    private fun removeLegacyPayloadOnce(profileId: Int) {
        if (!migratedLegacyProfileIds.add(profileId)) return
        ContinueWatchingEnrichmentStorage.removePayload(legacyStorageKey(profileId))
    }

    private fun advanceGeneration() {
        _generation.value += 1
    }

    private data class CacheScope(
        val profileId: Int,
        val source: WatchProgressSource,
    )

    internal fun continueWatchingEnrichmentStorageKey(
        profileId: Int,
        source: WatchProgressSource,
    ): String = ProfileScopedKey.of(
        baseKey = "${storageKey}_${source.name.lowercase()}",
        profileId = profileId,
    )

    internal fun legacyStorageKey(profileId: Int): String =
        ProfileScopedKey.of(storageKey, profileId)
}
