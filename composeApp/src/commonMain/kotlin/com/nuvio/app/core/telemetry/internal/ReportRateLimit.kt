package com.nuvio.app.core.telemetry.internal

/** One user report per [windowMs]. Plain state: the owner serialises access (a Mutex in the port). */
internal class ReportRateLimit(private val windowMs: Long = WINDOW_MS) {
    private var lastAtMs: Long? = null

    /** Seconds to wait, or null when a report may go now. */
    fun retryInSec(nowMs: Long): Int? {
        val last = lastAtMs ?: return null
        val remaining = windowMs - (nowMs - last)
        if (remaining <= 0 || remaining > windowMs) return null
        return ((remaining + 999) / 1000).toInt()
    }

    fun markSent(nowMs: Long) {
        lastAtMs = nowMs
    }

    companion object {
        const val WINDOW_MS = 120_000L
    }
}
