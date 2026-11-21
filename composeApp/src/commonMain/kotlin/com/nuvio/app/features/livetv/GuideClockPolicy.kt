package com.nuvio.app.features.livetv

/**
 * When the live guide's "now" clock should tick next.
 *
 * The screen reads the wall clock whenever it becomes visible (so a guide coming back from another tab or a
 * minimised window is never stale), then ticks on wall-clock boundaries of [TICK_MS]. Aligning to the boundary
 * keeps the now-line in step with the system clock instead of drifting by however long a tick took.
 */
internal object GuideClockPolicy {
    const val TICK_MS = 30_000L

    /** Milliseconds from [nowMs] to the next [tickMs] boundary; a full tick when exactly on one. */
    fun delayToNextTickMs(nowMs: Long, tickMs: Long = TICK_MS): Long {
        val intoTick = ((nowMs % tickMs) + tickMs) % tickMs
        return if (intoTick == 0L) tickMs else tickMs - intoTick
    }
}
