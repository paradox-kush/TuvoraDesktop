package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.stalker.StalkerProtocol
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/**
 * Classifies one throwable from a panel transport attempt into the guard's outcome vocabulary.
 * Platform `actual`s do the mapping because the network stacks throw platform types (OkHttp's
 * java.net hierarchy on Android/desktop, Ktor Darwin's NSError wrapper on iOS).
 *
 * Contract (mirrors [PanelRequestOutcome]'s own definitions):
 *  - the host never answered (timeout / DNS / refused / unreachable) -> CONNECTION_FAILURE
 *  - any other I/O failure (reset mid-transfer, TLS trouble, garbled bytes) -> CONNECTION_RESET —
 *    the host showed signs of life, so it is never counted, but proves nothing either
 *  - anything that is not an I/O failure happened AFTER a response arrived (the shared HTTP
 *    helpers throw plain IllegalStateException for non-2xx statuses and
 *    [com.nuvio.app.features.addons.EmptyResponseBodyException] for blank 2xx bodies, and parse
 *    errors need body bytes to choke on) -> HTTP_RESPONSE, which clears the record
 */
internal expect fun classifyPanelNetworkThrowable(t: Throwable): PanelRequestOutcome

/** [classifyPanelNetworkThrowable] plus the platform-free cases every stack shares. */
internal fun classifyPanelThrowable(t: Throwable): PanelRequestOutcome = when (t) {
    // A cancelled request proves nothing — and must release a held half-open trial slot.
    is CancellationException -> PanelRequestOutcome.CONNECTION_RESET
    // Our own refusal is never evidence about the wire (defensive: cannot happen with one wrap).
    is PanelHostFastFailException -> PanelRequestOutcome.CONNECTION_RESET
    else -> classifyPanelNetworkThrowable(t)
}

/**
 * The one process-wide [PanelHostGuard] (WP6) plus the reset helper every user-driven Retry
 * affordance calls. Shared by XtreamClient and StalkerSession — the guard is origin-keyed, so one
 * instance covers every panel of every playlist without the records colliding.
 *
 * The clock is the monotonic elapsed-time source the policy asks for: wall-clock jumps (NTP sync,
 * timezone changes) can neither reopen nor hold a breaker.
 */
object IptvPanelGuard {

    private val start = TimeSource.Monotonic.markNow()

    /** THE guard. Request choke points admit/report against this instance via [guardedPanelRequest]. */
    val guard: PanelHostGuard = PanelHostGuard { start.elapsedNow().inWholeMilliseconds }

    /**
     * User-driven Retry/refresh for [acc]: clears the breaker for the account's panel origin so the
     * retry is never met with a fast-fail. Call BEFORE the affordance's first request. No-op for
     * M3U playlists (no panel API to guard). Automatic retries and first-loads must NOT call this.
     */
    fun resetForAccount(acc: XtreamAccount) {
        panelOriginUrlOf(acc)?.let { guard.reset(it) }
    }

    /**
     * The URL whose origin keys [acc]'s breaker record — the SAME base the transports admit with:
     * Xtream requests start with [XtreamAccount.baseUrl]; Stalker requests start with the
     * normalized portal base (the session normalizes before building URLs, so reset must too).
     */
    internal fun panelOriginUrlOf(acc: XtreamAccount): String? = when (acc.sourceType) {
        SOURCE_TYPE_XTREAM -> acc.baseUrl
        SOURCE_TYPE_STALKER -> StalkerProtocol.normalizePortalBase(acc.baseUrl)
        else -> null   // M3U url/file playlists have no panel API
    }
}

/**
 * Admission-checks one panel transport attempt against this guard, then reports how it ended.
 *
 * - Refused admission throws the policy's own [PanelHostFastFailException] BEFORE any transport
 *   work. Its message wording is a contract: no `HTTP Error <code>`, no timeout words, no auth
 *   phrases — the Stalker error paths classify failures from text and must read this as a
 *   connection-level refusal (and it is not a [StalkerAuthException] twin, so it can never
 *   trigger a re-handshake).
 * - [discovery] marks a Stalker endpoint-discovery probe: admitted even while the breaker is
 *   open and its failures never count — but a success still clears the record.
 * - Every outcome is reported, including cancellation, so an abandoned half-open trial cannot
 *   wedge the breaker.
 */
internal suspend fun <T> PanelHostGuard.guardedPanelRequest(
    url: String,
    discovery: Boolean = false,
    attempt: suspend () -> T,
): T {
    val admission = when (val decision = admit(url, discovery)) {
        is PanelAdmission.FastFail -> throw decision.toException()
        is PanelAdmission.Allowed -> decision
    }
    val result = try {
        attempt()
    } catch (t: Throwable) {
        report(admission, classifyPanelThrowable(t))
        throw t
    }
    report(admission, PanelRequestOutcome.SUCCESS)
    return result
}
