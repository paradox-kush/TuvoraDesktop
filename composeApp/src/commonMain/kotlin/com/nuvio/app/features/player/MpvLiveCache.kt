package com.nuvio.app.features.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Pure helpers for the libmpv live back-buffer hooks ([PlayerEngineController.setLiveCacheBudget] /
 * [PlayerEngineController.liveSeekWindow]) so the numbers and the parsing are testable without an mpv core.
 * Used by the desktop native engine; the Android twin keeps its own copy over mpv's node API.
 *
 * Facts (mpv v0.41.0 demux.c thread_work -> update_opts): a runtime change of `demuxer-max-bytes`,
 * `demuxer-max-back-bytes` and `cache-secs` applies to the CURRENT file (pruned if shrunk); the demuxer reads ahead
 * while forward seconds < `cache-secs` AND forward bytes < `demuxer-max-bytes`, so a pause needs both raised.
 * `demuxer-cache-state.seekable-ranges` lists the stretches a seek can land in without a network read; read as a
 * string property it is JSON (a node property printed by mpv), which [parseSeekableRanges] reads.
 */
internal object MpvLiveCache {
    /** The runtime properties for one cache plan, in the order they are applied. */
    fun properties(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long): List<Pair<String, String>> = buildList {
        add("demuxer-max-bytes" to maxBytes.coerceAtLeast(0L).toString())
        add("demuxer-max-back-bytes" to maxBackBytes.coerceAtLeast(0L).toString())
        // cache-secs overrides demuxer-readahead-secs when larger, so it alone controls read-ahead depth.
        if (cacheSecs > 0L) add("cache-secs" to cacheSecs.coerceAtMost(MAX_CACHE_SECS).toString())
    }

    /** A seekable stretch, in mpv's time-pos seconds. */
    data class Range(val startSec: Double, val endSec: Double)

    /**
     * The ONE continuous stretch the playhead is in, in milliseconds on mpv's time-pos clock; null when nothing is
     * seekable or the position is unknown. A timeline discontinuity (a looping source, a splice) leaves several
     * ranges hours apart on mpv's clock; spanning them would make a window the playhead is not in, so:
     *  - a range holding the playhead wins (the one ending latest if several do);
     *  - otherwise the range ending latest before the playhead (the stretch it just left);
     *  - otherwise (the playhead is before everything) the range starting nearest after it.
     * The window is never wider than [keepMs] behind its end, the back buffer a live pause promises.
     */
    fun window(ranges: List<Range>, positionMs: Long, keepMs: Long = DEFAULT_KEEP_MS): LiveSeekWindow? {
        val valid = ranges.filter { it.startSec.isFinite() && it.endSec.isFinite() && it.endSec > it.startSec }
        if (valid.isEmpty() || positionMs <= 0L) return null
        val pos = positionMs / 1000.0
        val range = valid
            .filter { pos >= it.startSec - EDGE_TOLERANCE_SEC && pos <= it.endSec + EDGE_TOLERANCE_SEC }
            .maxByOrNull { it.endSec }
            ?: valid.filter { it.endSec < pos }.maxByOrNull { it.endSec }
            ?: valid.minByOrNull { it.startSec }!!
        val end = (range.endSec * 1000.0).toLong()
        val start = maxOf((range.startSec * 1000.0).toLong(), end - keepMs.coerceAtLeast(0L))
        return LiveSeekWindow(start, end, positionMs.coerceIn(start, end))
    }

    /**
     * `seekable-ranges` out of the `demuxer-cache-state` JSON text. Anything that is not that shape (empty, malformed,
     * a scalar, an entry missing a bound) yields what could be read - never a throw: the window is a nicety, the
     * playback must not depend on it.
     */
    fun parseSeekableRanges(json: String?): List<Range> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyList()
        val array = root["seekable-ranges"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            val obj = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
            val start = (obj["start"] as? JsonPrimitive)?.doubleOrNull
            val end = (obj["end"] as? JsonPrimitive)?.doubleOrNull
            if (start != null && end != null) Range(start, end) else null
        }
    }

    const val DEFAULT_KEEP_MS = 10 * 60_000L
    private const val EDGE_TOLERANCE_SEC = 0.5
    private const val MAX_CACHE_SECS = 1_000_000_000L
}
