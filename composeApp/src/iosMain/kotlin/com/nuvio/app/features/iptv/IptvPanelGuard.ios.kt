package com.nuvio.app.features.iptv

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.io.IOException
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut

/**
 * Ktor Darwin / NSURLSession mapping (the iOS transport in AddonPlatform.ios.kt).
 *
 * Ktor's own timeout types plus the NSURLError codes that mean "the host never answered" (timed
 * out, DNS, cannot connect, offline) count as [PanelRequestOutcome.CONNECTION_FAILURE]. Every
 * other engine error — NSURLErrorNetworkConnectionLost (the ECONNRESET analogue) included — is
 * inconclusive: being wrong here means refusing to talk to a live panel, so the mapping errs
 * toward contacting the host. A non-I/O throwable means a response arrived first (non-2xx
 * statuses and blank bodies surface as IllegalStateException from the shared helpers, parse
 * errors need body bytes) and clears the record.
 */
internal actual fun classifyPanelNetworkThrowable(t: Throwable): PanelRequestOutcome = when {
    t is ConnectTimeoutException ||
        t is SocketTimeoutException ||
        t is HttpRequestTimeoutException -> PanelRequestOutcome.CONNECTION_FAILURE

    t is DarwinHttpRequestException -> {
        val err = t.origin
        val neverAnswered = err.domain == NSURLErrorDomain && err.code in NEVER_ANSWERED_CODES
        if (neverAnswered) PanelRequestOutcome.CONNECTION_FAILURE else PanelRequestOutcome.CONNECTION_RESET
    }

    t is IOException -> PanelRequestOutcome.CONNECTION_RESET

    else -> PanelRequestOutcome.HTTP_RESPONSE
}

private val NEVER_ANSWERED_CODES: Set<Long> = setOf(
    NSURLErrorTimedOut,
    NSURLErrorCannotFindHost,
    NSURLErrorCannotConnectToHost,
    NSURLErrorDNSLookupFailed,
    NSURLErrorNotConnectedToInternet,
)
