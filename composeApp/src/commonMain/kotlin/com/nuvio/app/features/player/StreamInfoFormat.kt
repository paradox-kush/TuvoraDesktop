package com.nuvio.app.features.player

import kotlin.math.roundToInt

/**
 * Number formatting for the stream info panel, kept free of Compose so it can be tested
 * directly. Units are applied by the panel via string resources; these produce the value.
 *
 * Deliberately mirrors NuvioTV's `StreamInfoOverlay.formatResolution` / `formatBitrate`
 * so the same stream reads identically on a TV and on a phone. Change both or neither.
 */
internal object StreamInfoFormat {

    /**
     * `1920 × 804 (1080p)` — the raw dimensions plus the shorthand people actually ask
     * for. The tier is keyed on the larger dimension, which for landscape video is the
     * width: that keeps a 1920x804 scope film classified as 1080p instead of demoting it
     * to 720p on its letterboxed height.
     */
    fun resolution(width: Int?, height: Int?): String? {
        val w = width?.takeIf { it > 0 } ?: return null
        val h = height?.takeIf { it > 0 } ?: return null
        val maxDim = maxOf(w, h)
        val label = when {
            maxDim >= 3600 -> "4K"
            maxDim >= 2400 -> "1440p"
            maxDim >= 1800 -> "1080p"
            maxDim >= 1200 -> "720p"
            maxDim >= 800 -> "480p"
            else -> "${minOf(w, h)}p"
        }
        return "$w × $h ($label)"
    }

    /**
     * `23.976`, `50`. Whole rates drop the decimals so 50 fps does not read as "50.000",
     * but fractional NTSC rates keep enough precision to stay recognisable.
     */
    fun frameRate(fps: Float?): String? {
        val value = fps?.takeIf { it > 0f && it.isFinite() } ?: return null
        val thousandths = (value * 1000f).roundToInt()
        if (thousandths % 1000 == 0) return (thousandths / 1000).toString()
        val whole = thousandths / 1000
        val fraction = (thousandths % 1000).toString().padStart(3, '0').trimEnd('0')
        return "$whole.$fraction"
    }

    /** Bits per second in, `Mbps` or `kbps` out — whichever reads better at that scale. */
    fun bitrate(bitsPerSecond: Int?): Bitrate? {
        val bps = bitsPerSecond?.takeIf { it > 0 } ?: return null
        return if (bps >= 1_000_000) {
            // One decimal, always — matches TV, where 6 Mbps reads "6.0 Mbps".
            val tenths = (bps / 100_000.0).roundToInt()
            Bitrate("${tenths / 10}.${tenths % 10}", isMegabits = true)
        } else {
            Bitrate((bps / 1000.0).roundToInt().coerceAtLeast(1).toString(), isMegabits = false)
        }
    }

    /** Hertz in, whole kilohertz out: `48`. Matches TV's integer division. */
    fun sampleRate(hertz: Int?): String? =
        hertz?.takeIf { it > 0 }?.let { (it / 1000).toString() }

    /**
     * Speaker layout for channel counts that have a conventional name. Mono and stereo are
     * words and get localised by the caller, so they are not handled here.
     */
    fun channelLayout(channelCount: Int?): String? = when (channelCount) {
        3 -> "2.1"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> null
    }

    data class Bitrate(val value: String, val isMegabits: Boolean)

    /**
     * The shorthand tier on its own (`1080p`, `4K`) for the compact overlay, which has no
     * room for full dimensions. Derived from [resolution] so the two can never disagree.
     */
    fun qualityLabel(width: Int?, height: Int?): String? =
        resolution(width, height)?.substringAfterLast('(')?.removeSuffix(")")
}
