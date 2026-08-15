package com.nuvio.app.features.iptv

import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The panel's measured clock-pair offset ([ServerClockOffset]), once per account per SESSION.
 *
 * One memo shared by both consumers so the panel is asked at most once however the session goes:
 * the guide's epoch-skew correction ([XtreamEpochSkew]) asks lazily — only after a response has
 * voted LIAR, or when the catch-up table lane runs on auto — and [CatchUpEpgRepository.panelFacts]
 * reads the same value for the replay `start` math it has always done. `server_info` costs a whole
 * panel round trip and two of three real accounts are `max_connections=1`, which is why this is a
 * session memo and not a per-fetch call.
 *
 * Attempted-but-junk is remembered as null so a panel with no usable pair is asked once, not once
 * per channel. The cache is an immutable snapshot swapped whole: readers never lock, [forget] is
 * safe from any thread, and the fetch gate only serialises first-asks per process.
 */
internal object XtreamPanelClock {

    @Volatile
    private var measured: Map<String, Long?> = emptyMap()
    private val fetchGate = Mutex()

    /** The measured offset in ms (null = the panel's own clocks are junk), fetching at most once. */
    suspend fun measuredOffsetMs(account: XtreamAccount): Long? {
        measured[account.id]?.let { return it }
        if (account.id in measured) return null   // attempted, junk
        fetchGate.withLock {
            if (account.id in measured) return measured[account.id]
            val value = XtreamClient.serverClockOffsetMs(account)
            measured = measured + (account.id to value)
            return value
        }
    }

    /** Drops one playlist's memo — its options changed, so the next ask re-measures. */
    fun forget(accountId: String) {
        measured = measured - accountId
    }
}
