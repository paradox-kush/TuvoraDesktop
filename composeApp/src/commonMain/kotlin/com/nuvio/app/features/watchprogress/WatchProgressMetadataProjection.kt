package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.tracking.WatchProgressSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class WatchProgressMetadataKey(
    val metaId: String,
    val metaType: String,
)

internal fun WatchProgressEntry.metadataKey(): WatchProgressMetadataKey = WatchProgressMetadataKey(
    metaId = parentMetaId,
    metaType = parentMetaType.ifBlank { contentType },
)

internal fun enrichWatchProgressEntry(
    current: WatchProgressEntry,
    meta: MetaDetails,
): WatchProgressEntry {
    val episodeVideo = if (current.seasonNumber != null && current.episodeNumber != null) {
        meta.videos.firstOrNull { video ->
            video.season == current.seasonNumber && video.episode == current.episodeNumber
        }
    } else {
        null
    }
    return current.copy(
        videoId = if (current.source != WatchProgressSourceLocal && episodeVideo != null) {
            episodeVideo.id.takeIf(String::isNotBlank) ?: current.videoId
        } else {
            current.videoId
        },
        title = meta.name.takeIf(String::isNotBlank) ?: current.title,
        poster = meta.poster?.takeIf(String::isNotBlank) ?: current.poster,
        background = meta.background?.takeIf(String::isNotBlank) ?: current.background,
        logo = meta.logo?.takeIf(String::isNotBlank) ?: current.logo,
        episodeTitle = episodeVideo?.title?.takeIf(String::isNotBlank) ?: current.episodeTitle,
        episodeThumbnail = episodeVideo?.thumbnail?.takeIf(String::isNotBlank) ?: current.episodeThumbnail,
        pauseDescription = episodeVideo?.overview?.takeIf(String::isNotBlank)
            ?: meta.description?.takeIf(String::isNotBlank)
            ?: current.pauseDescription,
    )
}

/**
 * B64 parity (NuvioTV T1): metadata hydration only PATCHES an entry that is still stored under
 * [progressKey] — it never creates one. Hydration starts from a snapshot of the entries and applies its
 * results seconds later; a re-key (or a removal / a fresher playback save) can land in between, and a
 * "read, then upsert" write-back would bring the old key back (a duplicate Continue Watching card that
 * opens an empty page). The lookup and the write happen under one call so the caller can hold its lock
 * across both. Returns true when the stored entry changed.
 */
internal fun MutableMap<String, WatchProgressEntry>.patchExistingWithMetadata(
    progressKey: String,
    meta: MetaDetails,
): Boolean {
    val current = this[progressKey] ?: return false
    val enriched = enrichWatchProgressEntry(current = current, meta = meta)
    if (enriched == current) return false
    val resolved = enriched.withResolvedProgressKey()
    this[resolved.resolvedProgressKey()] = resolved
    return true
}

internal fun WatchProgressEntry.needsRemoteMetadataEnrichment(): Boolean =
    title.isBlank() ||
        title.equals(parentMetaId, ignoreCase = true) ||
        poster.isNullOrBlank() ||
        background.isNullOrBlank()

internal class ProviderProgressMetadataOverlay {
    private val lock = SynchronizedObject()
    private var source: WatchProgressSource? = null
    private val metadataByKey = mutableMapOf<WatchProgressMetadataKey, MetaDetails>()

    fun clear() {
        synchronized(lock) {
            source = null
            metadataByKey.clear()
        }
    }

    fun put(
        source: WatchProgressSource,
        key: WatchProgressMetadataKey,
        metadata: MetaDetails,
    ): Boolean = synchronized(lock) {
        if (this.source != source) {
            this.source = source
            metadataByKey.clear()
        }
        val previous = metadataByKey.put(key, metadata)
        previous != metadata
    }

    fun project(
        source: WatchProgressSource,
        entries: Collection<WatchProgressEntry>,
    ): List<WatchProgressEntry> {
        val metadata = synchronized(lock) {
            if (this.source == source) metadataByKey.toMap() else emptyMap()
        }
        if (metadata.isEmpty()) return entries.toList()
        return entries.map { entry ->
            metadata[entry.metadataKey()]
                ?.let { meta -> enrichWatchProgressEntry(current = entry, meta = meta) }
                ?: entry
        }
    }
}
