package com.nuvio.app.features.iptv

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorClientCertificateRejected
import platform.Foundation.NSURLErrorClientCertificateRequired
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut

/**
 * Ktor Darwin / NSURLSession -> [FailoverFailureKind] (Step 0.3). NSURLErrorNotConnectedToInternet is
 * deliberately NOT mapped: the device is offline, every server would fail the same way.
 */
internal actual fun platformFailoverFailureKind(t: Throwable): FailoverFailureKind? = when (t) {
    is ConnectTimeoutException -> FailoverFailureKind.CONNECT_TIMEOUT
    is SocketTimeoutException, is HttpRequestTimeoutException -> FailoverFailureKind.READ_TIMEOUT
    is DarwinHttpRequestException -> {
        val err = t.origin
        if (err.domain != NSURLErrorDomain) null else when (err.code) {
            NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed -> FailoverFailureKind.DNS
            NSURLErrorCannotConnectToHost -> FailoverFailureKind.CONNECT_REFUSED
            NSURLErrorTimedOut -> FailoverFailureKind.CONNECT_TIMEOUT
            in TLS_CODES -> FailoverFailureKind.TLS_HANDSHAKE
            else -> null
        }
    }
    else -> null
}

private val TLS_CODES: Set<Long> = setOf(
    NSURLErrorSecureConnectionFailed,
    NSURLErrorServerCertificateHasBadDate,
    NSURLErrorServerCertificateUntrusted,
    NSURLErrorServerCertificateHasUnknownRoot,
    NSURLErrorServerCertificateNotYetValid,
    NSURLErrorClientCertificateRejected,
    NSURLErrorClientCertificateRequired,
)
