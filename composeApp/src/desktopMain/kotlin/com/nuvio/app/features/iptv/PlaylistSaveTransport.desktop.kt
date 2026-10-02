package com.nuvio.app.features.iptv

import java.io.IOException
import java.net.MalformedURLException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * OkHttp/java.net mapping for the playlist-save message (the desktop transport in
 * AddonPlatform.desktop.kt) — same JVM rules as the Android actual. TLS trouble —
 * SSLHandshakeException, SSLPeerUnverifiedException and the certificate failures they wrap — is
 * the B23 class and gets its own sentence. Every other I/O
 * failure (DNS, refused, unreachable, timeout, reset) means the save could not talk to the server.
 */
internal actual fun classifyPlatformTransportFailure(t: Throwable): PlaylistTransportFailure? = when (t) {
    is SSLException, is CertificateException -> PlaylistTransportFailure.SECURE_CONNECTION
    is MalformedURLException -> PlaylistTransportFailure.INVALID_ADDRESS
    is IOException -> PlaylistTransportFailure.UNREACHABLE
    else -> null
}
