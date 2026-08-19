package com.nuvio.app.features.streams

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CachedStreamLink(
    val url: String = "",
    val streamName: String,
    val addonName: String,
    val addonId: String,
    val cachedAtMs: Long,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val filename: String? = null,
    val videoSize: Long? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String> = emptyList(),
    val bingeGroup: String? = null,
    val streamType: String? = null,
    val contentLanguage: String? = null,
)

internal expect fun epochMs(): Long

object StreamLinkCacheRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun contentKey(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): String {
        val normalizedType = type.lowercase()
        return if (!parentMetaId.isNullOrBlank() && season != null && episode != null) {
            "$normalizedType|${parentMetaId.trim()}|s$season|e$episode|$videoId"
        } else {
            "$normalizedType|$videoId"
        }
    }

    fun save(
        contentKey: String,
        url: String,
        streamName: String,
        addonName: String,
        addonId: String,
        requestHeaders: Map<String, String> = emptyMap(),
        responseHeaders: Map<String, String> = emptyMap(),
        filename: String? = null,
        videoSize: Long? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        sources: List<String> = emptyList(),
        bingeGroup: String? = null,
        streamType: String? = null,
        contentLanguage: String? = null,
    ) {
        if (url.isNotBlank() && url.hasLikelyExpiringPlaybackCredentials()) {
            remove(contentKey)
            return
        }
        // An IPTV link is only valid for as long as the provider's catalog stands still, and it
        // does not: a panel can renumber its whole VOD catalog overnight (observed live —
        // "synced movie index: +6249 -6253"), after which the cached stream id is gone and the
        // panel answers the replayed URL with 401. Stalker links are worse still: single-use.
        // Re-resolving costs one catalog lookup the matcher performs anyway, so never cache these.
        if (isIptvAddon(addonId)) {
            remove(contentKey)
            return
        }

        val entry = CachedStreamLink(
            url = url,
            streamName = streamName,
            addonName = addonName,
            addonId = addonId,
            cachedAtMs = epochMs(),
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders,
            filename = filename,
            videoSize = videoSize,
            infoHash = infoHash,
            fileIdx = fileIdx,
            sources = sources,
            bingeGroup = bingeGroup,
            streamType = streamType,
            contentLanguage = contentLanguage,
        )
        val payload = json.encodeToString(CachedStreamLink.serializer(), entry)
        StreamLinkCacheStorage.saveEntry(hashedKey(contentKey), payload)
    }

    fun remove(contentKey: String) {
        StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
    }

    fun getValid(contentKey: String, maxAgeMs: Long): CachedStreamLink? {
        if (maxAgeMs <= 0L) return null
        val raw = StreamLinkCacheStorage.loadEntry(hashedKey(contentKey)) ?: return null
        val entry = runCatching {
            json.decodeFromString(CachedStreamLink.serializer(), raw)
        }.getOrNull() ?: run {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        val age = epochMs() - entry.cachedAtMs
        if (entry.cachedAtMs <= 0L || age > maxAgeMs) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        if (entry.url.isNotBlank() && entry.url.hasLikelyExpiringPlaybackCredentials()) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        // Also drop IPTV entries written by an earlier build, so an upgrade doesn't keep replaying
        // a link whose stream id the provider has since renumbered away.
        if (isIptvAddon(entry.addonId)) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        if (entry.url.isBlank() && entry.infoHash.isNullOrBlank()) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        return entry
    }

    /**
     * True for a stream produced by an IPTV account rather than an addon/debrid provider: the
     * direct hybrid lane ("xtream") and the TMDB-matched lane ("xtream-match:<accountId>").
     */
    internal fun isIptvAddon(addonId: String?): Boolean {
        if (addonId.isNullOrBlank()) return false
        return addonId == "xtream" ||
            com.nuvio.app.core.contracts.IptvContentClassifierAccess.classifier.isXtreamStreamGroup(addonId)
    }

    private fun hashedKey(contentKey: String): String {
        val hash = contentKey.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "stream_link_$hash"
    }
}
