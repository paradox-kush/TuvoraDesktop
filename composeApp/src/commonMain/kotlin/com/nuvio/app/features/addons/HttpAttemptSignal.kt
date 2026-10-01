package com.nuvio.app.features.addons

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/**
 * Step 0.3b — how a failover attempt talks to the platform HTTP helpers without changing their
 * signatures. The IPTV failover executor installs one of these in the coroutine context of each
 * attempt; the helpers ([httpGetText], [httpGetTextWithHeaders], [httpStreamLines], …) then
 *  - apply [connectTimeoutMs] to that request's CONNECT phase only (read/overall timeouts unchanged), and
 *  - call [signalHttpHeaders] once the response STATUS is known to be 2xx and before any body byte is
 *    read, which is where a racing attempt reports "I answered" and (if another attempt already won)
 *    is parked until it is cancelled — so a loser never reads, let alone downloads, a body.
 *
 * Outside a failover walk there is no element, and every helper behaves exactly as it always did.
 */
class HttpAttemptSignal(
    val connectTimeoutMs: Long?,
    /** Called with true/false when the attempt starts/stops waiting on a LOCAL queue (see [awaitingLocally]). */
    internal val onLocalWait: ((Boolean) -> Unit)? = null,
    private val onHeaders: (suspend () -> Unit)? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HttpAttemptSignal>

    private var fired = false

    internal suspend fun fire() {
        val callback = onHeaders ?: return
        if (fired) return
        fired = true
        callback()
    }

    /** Same connect timeout, no header callback. */
    fun silenced(): HttpAttemptSignal = HttpAttemptSignal(connectTimeoutMs, onLocalWait, null)
}

/** The CONNECT timeout the current failover attempt asks for, or null outside a failover walk. */
suspend fun failoverConnectTimeoutMs(): Long? = coroutineContext[HttpAttemptSignal]?.connectTimeoutMs

/**
 * Called by a platform HTTP helper right after it saw a 2xx status line and before reading the body.
 * No-op outside a racing attempt. May suspend until the attempt is cancelled (it lost the race).
 */
suspend fun signalHttpHeaders() {
    coroutineContext[HttpAttemptSignal]?.fire()
}

/**
 * Runs [block] so that the HTTP calls inside it do NOT count as "the answer" of a racing attempt
 * (a Stalker handshake is preparation, not the response the race is about). Connect timeout stays.
 */
suspend fun <T> withoutHttpHeadersSignal(block: suspend () -> T): T {
    val current = coroutineContext[HttpAttemptSignal] ?: return block()
    return withContext(current.silenced()) { block() }
}

/**
 * Runs [block] — a wait on a LOCAL queue (a per-portal connection permit, "hold browse traffic while a
 * stream plays") — telling a racing failover walk that this time is not the server's latency, so the
 * stagger clock stops while it lasts. A healthy main that merely queued behind its own siblings must
 * never be taken for a slow one. No-op outside a failover walk.
 */
suspend fun <T> awaitingLocally(block: suspend () -> T): T {
    val notify = coroutineContext[HttpAttemptSignal]?.onLocalWait ?: return block()
    notify(true)
    try {
        return block()
    } finally {
        notify(false)
    }
}
