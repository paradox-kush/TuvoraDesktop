package com.nuvio.app.features.iptv

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * OkHttp/java.net mapping (the Android transport in AddonPlatform.android.kt).
 *
 * Only the failures that mean "the host never answered" count as [PanelRequestOutcome.CONNECTION_FAILURE]:
 * timeouts (SocketTimeoutException covers OkHttp's connect AND read timeouts), DNS
 * (UnknownHostException), refused/unreachable (ConnectException, NoRouteToHostException,
 * PortUnreachableException). Every other [IOException] — ECONNRESET (SocketException), truncated
 * bodies (EOFException), TLS trouble (SSLException), OkHttp call-timeout/cancel
 * (InterruptedIOException) — is inconclusive: being wrong here means refusing to talk to a live
 * panel, so the mapping errs toward contacting the host. A non-I/O throwable means a response
 * arrived first (non-2xx statuses and blank bodies surface as IllegalStateException from the
 * shared helpers, parse errors need body bytes) and clears the record.
 */
internal actual fun classifyPanelNetworkThrowable(t: Throwable): PanelRequestOutcome = when (t) {
    is UnknownHostException,
    is ConnectException,
    is NoRouteToHostException,
    is PortUnreachableException,
    is SocketTimeoutException -> PanelRequestOutcome.CONNECTION_FAILURE

    is IOException -> PanelRequestOutcome.CONNECTION_RESET

    else -> PanelRequestOutcome.HTTP_RESPONSE
}
