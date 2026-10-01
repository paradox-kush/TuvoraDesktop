package com.nuvio.app.features.iptv

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * OkHttp/java.net -> [FailoverFailureKind] (Step 0.3) — same JVM rules as the Android actual. OkHttp reports both connect and read timeouts
 * as [SocketTimeoutException]; its connect variant says so in the message. Anything else (ECONNRESET,
 * truncated bodies, call cancellation) is not recognised here and ends as OTHER — no failover.
 */
internal actual fun platformFailoverFailureKind(t: Throwable): FailoverFailureKind? = when (t) {
    is UnknownHostException -> FailoverFailureKind.DNS
    is ConnectException, is NoRouteToHostException, is PortUnreachableException -> FailoverFailureKind.CONNECT_REFUSED
    is SocketTimeoutException ->
        if (t.message?.contains("connect", ignoreCase = true) == true) FailoverFailureKind.CONNECT_TIMEOUT
        else FailoverFailureKind.READ_TIMEOUT
    is SSLHandshakeException, is SSLPeerUnverifiedException -> FailoverFailureKind.TLS_HANDSHAKE
    else -> null
}
