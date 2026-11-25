package com.nuvio.app.core.telemetry.api

/**
 * Builds and validates the `session_health` count keys of the playback telemetry contract. A key is
 * `<name>:<dim1>:<dim2>...` made only of values from the closed sets below, so the key space is small and no URL,
 * host, title or message text can ride in one. The same key strings are used on every platform.
 *
 * Inputs are normalised, never trusted: an unknown engine becomes `other`, an unknown fallback trigger becomes
 * `other`, and a signal whose kind is unknown yields null (nothing is counted).
 */
object PlaybackTelemetryKeys {
    val KINDS: Set<String> = setOf("live", "vod", "preview", "multiview_tile")
    val ENGINES: Set<String> = setOf("media3", "libmpv", "avplayer", "other")
    val FALLBACK_TRIGGERS: Set<String> = setOf(
        "manifest_invalid", "demux_failed", "decoder_failed", "renderer_failed", "network", "timeout", "watchdog", "user", "other",
    )
    val STALL_OUTCOMES: Set<String> = setOf("recovered", "gave_up")
    val TEARDOWN_OUTCOMES: Set<String> = setOf("clean", "slow", "failed")
    val MULTIVIEW_EVENTS: Set<String> = setOf("opened", "closed", "tile_failed")
    val END_REASONS: Set<String> = setOf("user", "error", "memory", "app_background", "other")

    // Failure-diagnostic vocabularies (contract B). Desktop reuses its existing properties where they carry the same fact.
    val EXT_CLASSES: Set<String> = setOf("m3u8", "ts", "mp4", "mkv", "none", "other")
    val CONTENT_TYPE_CLASSES: Set<String> = setOf("hls", "mp2t", "octet", "html", "text", "video", "other")
    val SOURCE_TYPES: Set<String> = setOf("xtream", "stalker", "m3u", "file", "addon", "media_server")
    val HTTP_CLASSES: Set<String> = setOf("none", "2xx", "3xx", "401_403", "404", "456", "4xx_other", "5xx")

    const val MAX_KEY_LENGTH = 64

    /** Engine labels the apps use (`exoplayer`, `exo`, `mpv`, ...) folded into the contract's four values. */
    fun engine(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "media3", "exoplayer", "exo" -> "media3"
        "libmpv", "mpv" -> "libmpv"
        "avplayer" -> "avplayer"
        else -> "other"
    }

    /** One of [KINDS], or null when the label is not a playback kind (nothing is counted then). */
    fun kind(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it in KINDS }

    fun fallbackTrigger(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it in FALLBACK_TRIGGERS } ?: "other"

    fun ttff(kindLabel: String, engineLabel: String, ms: Long): String? =
        kind(kindLabel)?.let { "ttff:$it:${engine(engineLabel)}:${PlaybackTelemetryBuckets.ttff(ms)}" }

    fun fallback(fromEngine: String, toEngine: String, trigger: String?): String =
        "fallback:${engine(fromEngine)}:${engine(toEngine)}:${fallbackTrigger(trigger)}"

    /** `stall:<kind>:<outcome>`; [recovered] false is `gave_up` (the viewer or the ladder ended it). */
    fun stall(kindLabel: String, recovered: Boolean): String? =
        kind(kindLabel)?.let { "stall:$it:${if (recovered) "recovered" else "gave_up"}" }

    fun stallMs(kindLabel: String, ms: Long): String? =
        kind(kindLabel)?.let { "stall_ms:$it:${PlaybackTelemetryBuckets.stall(ms)}" }

    /** `teardown:<engine>:<outcome>`: failed beats slow (over [PlaybackTelemetryBuckets.SLOW_TEARDOWN_MS]) beats clean. */
    fun teardown(engineLabel: String, ms: Long, failed: Boolean): String =
        "teardown:${engine(engineLabel)}:${teardownOutcome(ms, failed)}"

    fun teardownOutcome(ms: Long, failed: Boolean): String = when {
        failed -> "failed"
        ms > PlaybackTelemetryBuckets.SLOW_TEARDOWN_MS -> "slow"
        else -> "clean"
    }

    fun multiview(event: String): String? = event.takeIf { it in MULTIVIEW_EVENTS }?.let { "multiview:$it" }

    /** Max-gauge on close; tiles 1..9. */
    fun multiviewTilesMax(tiles: Int): String? = tiles.takeIf { it in 1..9 }?.let { "multiview_tiles_max:$it" }

    /**
     * True only for a key this object could have built. The service refuses anything else, so a typo or a future
     * free-text value can never become a `session_health` property.
     */
    fun isValid(key: String): Boolean {
        if (key.length > MAX_KEY_LENGTH) return false
        val p = key.split(':')
        return when (p.firstOrNull()) {
            "ttff" -> p.size == 4 && p[1] in KINDS && p[2] in ENGINES && p[3] in PlaybackTelemetryBuckets.TTFF
            "fallback" -> p.size == 4 && p[1] in ENGINES && p[2] in ENGINES && p[3] in FALLBACK_TRIGGERS
            "stall" -> p.size == 3 && p[1] in KINDS && p[2] in STALL_OUTCOMES
            "stall_ms" -> p.size == 3 && p[1] in KINDS && p[2] in PlaybackTelemetryBuckets.STALL
            "teardown" -> p.size == 3 && p[1] in ENGINES && p[2] in TEARDOWN_OUTCOMES
            "multiview" -> p.size == 2 && p[1] in MULTIVIEW_EVENTS
            "multiview_tiles_max" -> p.size == 2 && (p[1].toIntOrNull() ?: 0) in 1..9
            else -> false
        }
    }

    /**
     * `app_exit` facts about the previous run, from its persisted breadcrumb: `prev_playing`, and while it was playing
     * `prev_kind` / `prev_engine`. Empty when the breadcrumb does not say (an older marker). `prev_tiles` is the
     * multiview tile count and is added only by platforms that have multiview.
     */
    fun previousRun(playing: Boolean?, kindLabel: String?, engineLabel: String?): Map<String, Any> {
        if (playing == null) return emptyMap()
        return buildMap {
            put("prev_playing", playing)
            if (playing) {
                kind(kindLabel)?.let { put("prev_kind", it) }
                put("prev_engine", engine(engineLabel))
            }
        }
    }

    /** Extension class of a stream path (path only, never the host). */
    fun extClass(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") -> "m3u8"
            path.endsWith(".ts") -> "ts"
            path.endsWith(".mp4") -> "mp4"
            path.endsWith(".mkv") -> "mkv"
            else -> "other"
        }
    }

    /** HTTP status of a failed open, as a class. Null/0 is `none`. */
    fun httpClass(status: Int?): String = when {
        status == null || status <= 0 -> "none"
        status in 200..299 -> "2xx"
        status in 300..399 -> "3xx"
        status == 401 || status == 403 -> "401_403"
        status == 404 -> "404"
        status == 456 -> "456"
        status in 400..499 -> "4xx_other"
        status in 500..599 -> "5xx"
        else -> "none"
    }

    /** Content-Type header of a refused open as a class. Parameters (`; charset=`) are ignored; the value is never sent. */
    fun contentTypeClass(header: String?): String {
        val t = header?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            t.isEmpty() -> "other"
            t.contains("mpegurl") -> "hls"
            t == "video/mp2t" -> "mp2t"
            t == "application/octet-stream" || t == "binary/octet-stream" -> "octet"
            t == "text/html" -> "html"
            t.startsWith("text/") -> "text"
            t.startsWith("video/") -> "video"
            else -> "other"
        }
    }
}
