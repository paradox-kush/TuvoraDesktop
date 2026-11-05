package com.nuvio.app.features.recordings.internal

/**
 * How a desktop alarm waits. The JVM has no "time was set" or "timezone changed" broadcast, so an
 * alarm never sleeps its whole way: it sleeps in chunks of at most [MAX_CHUNK_MS] and asks the wall
 * clock again after every wake. A clock moved forward fires within a chunk; a clock moved back just
 * waits longer; a laptop that slept fires on wake. Pure so every branch tests without a clock.
 */
internal object RecordingAlarmTiming {
    const val MAX_CHUNK_MS = 60_000L

    sealed interface Step {
        /** The fire time has come (or passed). */
        data object FireNow : Step

        /** Sleep this long, then ask again. Always in 1..[MAX_CHUNK_MS]. */
        data class Sleep(val ms: Long) : Step
    }

    fun next(nowMs: Long, fireAtMs: Long, maxChunkMs: Long = MAX_CHUNK_MS): Step {
        val remaining = fireAtMs - nowMs
        return if (remaining <= 0L) Step.FireNow else Step.Sleep(remaining.coerceAtMost(maxChunkMs))
    }
}
