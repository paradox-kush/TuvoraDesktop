package com.nuvio.app.features.iptv

import com.nuvio.app.features.trakt.TraktPlatformClock

/**
 * Where a setup code waits between being typed (or opened from a link) and being redeemed — for example
 * while the person signs in first. MEMORY ONLY: never written to disk, logged, sent to analytics,
 * attached to a crash breadcrumb, or passed as a saved navigation argument. It is cleared on success,
 * on Cancel, and 30 minutes after being set.
 */
internal class SetupCodeHolder(
    private val clock: () -> Long = { TraktPlatformClock.nowEpochMs() },
    private val ttlMs: Long = TTL_MS,
) {
    private var code: String? = null
    private var setAtMs: Long = 0L

    /** Keeps the normalized code; returns false (and holds nothing) when [input] is not a valid code. */
    fun set(input: String): Boolean {
        val parsed = SetupCode.parse(input)
        code = parsed
        setAtMs = clock()
        return parsed != null
    }

    /** The held code, or null when none was set or it is older than the time limit (which clears it). */
    fun peek(): String? {
        val held = code ?: return null
        if (clock() - setAtMs >= ttlMs) {
            code = null
            return null
        }
        return held
    }

    fun hasCode(): Boolean = peek() != null

    fun clear() {
        code = null
        setAtMs = 0L
    }

    companion object {
        const val TTL_MS = 30L * 60_000L

        /** The process-wide holder the setup screens share. */
        val shared = SetupCodeHolder()
    }
}
