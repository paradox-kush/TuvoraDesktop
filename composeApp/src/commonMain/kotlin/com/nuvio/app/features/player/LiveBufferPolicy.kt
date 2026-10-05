package com.nuvio.app.features.player

/**
 * F13 — adjustable / automatic buffer length for live IPTV (GitHub tuvora#10).
 *
 * One setting, in seconds: [AUTO] keeps each engine's tuned live default (what every platform ships
 * today), any other value asks the engine to keep that many seconds ahead. It is a LIVE setting only:
 * VOD and catch-up keep their own buffering. Pure: the engines map the [LiveBufferPlan] themselves.
 *
 * What "seconds" means per engine:
 *  - ExoPlayer: min buffer = the target, max = twice it (room to refill), start = a short fixed
 *    threshold so channel zapping stays fast, after-rebuffer = the target capped at 10 s (a stall is
 *    when a bigger cushion is worth the wait).
 *  - libmpv (mpv manual, `--cache-secs` / `--cache-pause-wait`): with the cache on, mpv's default
 *    readahead is limited only by `demuxer-max-bytes`, so `cache-secs` = the target CAPS it (a small
 *    value keeps you nearer the live edge) and `cache-pause-wait` = the after-rebuffer cushion (mpv's
 *    default is 1 s — the knob a stuttering provider actually needs). The byte caps stay as they are
 *    (memory tier), so on a very high bitrate the bytes, not the seconds, are the real limit.
 */
object LiveBufferPolicy {
    const val AUTO = 0
    val CHOICES_SECONDS: List<Int> = listOf(AUTO, 5, 10, 20, 30, 60)
    private const val START_MS = 1_500
    private const val MAX_REBUFFER_MS = 10_000

    /** Snap a stored value onto the offered choices (largest choice not above it); junk -> [AUTO]. */
    fun normalize(seconds: Int?): Int {
        val value = seconds ?: return AUTO
        if (value <= 0) return AUTO
        return CHOICES_SECONDS.filter { it != AUTO && it <= value }.maxOrNull() ?: CHOICES_SECONDS.first { it != AUTO }
    }

    /** Live only: VOD and catch-up recordings keep their own buffering. */
    fun planFor(seconds: Int?, isLive: Boolean, isCatchUp: Boolean): LiveBufferPlan? =
        if (isLive && !isCatchUp) plan(seconds) else null

    /** Null = AUTO: the engine keeps its tuned default. */
    fun plan(seconds: Int?): LiveBufferPlan? {
        val s = normalize(seconds)
        if (s == AUTO) return null
        val targetMs = s * 1_000
        return LiveBufferPlan(
            minBufferMs = targetMs,
            maxBufferMs = targetMs * 2,
            startMs = START_MS,
            rebufferMs = minOf(targetMs, MAX_REBUFFER_MS),
        )
    }

    fun mpvProperties(plan: LiveBufferPlan): List<Pair<String, String>> = listOf(
        "cache" to "yes",
        "cache-secs" to (plan.minBufferMs / 1000).toString(),
        "demuxer-readahead-secs" to (plan.minBufferMs / 1000).toString(),
        "cache-pause-wait" to (plan.rebufferMs / 1000).toString(),
    )
}

data class LiveBufferPlan(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val startMs: Int,
    val rebufferMs: Int,
)
