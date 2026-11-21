package com.nuvio.app.features.updater

/**
 * Keeps the read loop of the downloader from becoming a state change per read: a 58 MB installer reports about
 * 7,000 times, and every report used to rebuild the updater state and wake every screen reading it (B274).
 * Emits when the whole percent moved and at least [minIntervalMs] passed since the last emission, plus the first
 * and the final 100%.
 */
class UpdateProgressThrottle(private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS) {
    private var lastPercent = -1
    private var lastEmitAtMs = 0L

    /** Returns the progress (0..1) to publish now, or null to skip this report. */
    fun accept(downloadedBytes: Long, totalBytes: Long?, nowMs: Long): Float? {
        if (totalBytes == null || totalBytes <= 0L) return null
        val percent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        val publish = when {
            lastPercent < 0 -> true
            percent == lastPercent -> false
            percent == 100 -> true
            else -> nowMs - lastEmitAtMs >= minIntervalMs
        }
        if (!publish) return null
        lastPercent = percent
        lastEmitAtMs = nowMs
        return percent / 100f
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 250L
    }
}
