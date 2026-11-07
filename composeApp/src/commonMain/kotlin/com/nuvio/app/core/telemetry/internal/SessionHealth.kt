package com.nuvio.app.core.telemetry.internal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Per-session success counters, sent as one `session_health` event instead of a stream of successes. */
internal class SessionHealth {
    private val lock = SynchronizedObject()
    private val counts = HashMap<String, Int>()
    private var peakUsedMb = 0L

    fun count(key: String) = synchronized(lock) {
        // Bounded: a runaway key space cannot grow the map without limit.
        if (counts.size < MAX_KEYS || counts.containsKey(key)) counts[key] = (counts[key] ?: 0) + 1
    }

    fun observeUsedMb(mb: Long) = synchronized(lock) { if (mb > peakUsedMb) peakUsedMb = mb }

    /** Snapshot copied under the lock, or null when nothing happened. */
    fun snapshot(): Map<String, Any>? = synchronized(lock) {
        if (counts.isEmpty() && peakUsedMb == 0L) return@synchronized null
        buildMap {
            for ((k, v) in counts) put("n.$k", v)
            if (peakUsedMb > 0) put("peak_used_mb", peakUsedMb)
        }
    }

    companion object {
        const val MAX_KEYS = 64
    }
}
