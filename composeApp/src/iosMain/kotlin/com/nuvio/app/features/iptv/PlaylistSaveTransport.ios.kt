package com.nuvio.app.features.iptv

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.io.IOException
import platform.Foundation.NSURLErrorBadURL
import platform.Foundation.NSURLErrorClientCertificateRejected
import platform.Foundation.NSURLErrorClientCertificateRequired
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorUnsupportedURL

/**
 * Ktor Darwin / NSURLSession mapping for the playlist-save message (the iOS and tvOS transport in
 * AddonPlatform.ios.kt). The NSURLError TLS/certificate codes are the B23 class; a bad or
 * unsupported URL is an invalid address; every other engine error (DNS, cannot connect, timed out,
 * offline, connection lost) means the save could not talk to the server.
 */
internal actual fun classifyPlatformTransportFailure(t: Throwable): PlaylistTransportFailure? = when (t) {
    is DarwinHttpRequestException -> {
        val err = t.origin
        when {
            err.domain != NSURLErrorDomain -> PlaylistTransportFailure.UNREACHABLE
            err.code in SECURE_CONNECTION_CODES -> PlaylistTransportFailure.SECURE_CONNECTION
            err.code in INVALID_ADDRESS_CODES -> PlaylistTransportFailure.INVALID_ADDRESS
            else -> PlaylistTransportFailure.UNREACHABLE
        }
    }
    is IOException -> PlaylistTransportFailure.UNREACHABLE
    else -> null
}

private val SECURE_CONNECTION_CODES: Set<Long> = setOf(
    NSURLErrorSecureConnectionFailed,
    NSURLErrorServerCertificateHasBadDate,
    NSURLErrorServerCertificateUntrusted,
    NSURLErrorServerCertificateHasUnknownRoot,
    NSURLErrorServerCertificateNotYetValid,
    NSURLErrorClientCertificateRejected,
    NSURLErrorClientCertificateRequired,
)

private val INVALID_ADDRESS_CODES: Set<Long> = setOf(NSURLErrorBadURL, NSURLErrorUnsupportedURL)
