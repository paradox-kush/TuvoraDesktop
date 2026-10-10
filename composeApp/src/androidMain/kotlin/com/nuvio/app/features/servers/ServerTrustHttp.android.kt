package com.nuvio.app.features.servers

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.internal.tls.OkHostnameVerifier
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * One OkHttp client per server authority (never trust-all: only a certificate pinned for exactly this authority passes when system validation fails). System validation runs first; ONLY when it fails does the shared
 * [ServerCertTrustPolicy] decide (via [ServerTrust.check]) whether a certificate this device pinned - or the
 * user is about to be asked to pin - applies. A publicly trusted chain is never pinned. Redirects are off
 * (a base path redirect is the user's to type, design 5.4); retries are the caller's.
 */
internal actual fun createTrustedServerHttpClient(authority: String, trust: ServerTrust): HttpClient {
    val system = systemTrustManager()
    val tofu = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val allowed = trust.check(authority, false, classify(e, chain), fingerprint(chain[0]))
                if (!allowed) throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
    val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tofu), null) }
    // A pinned self-signed certificate often carries no SAN for the LAN address it is reached by: hostname
    // verification is waived ONLY for the exact certificate the user pinned.
    val hostnameVerifier = HostnameVerifier { host, session ->
        if (OkHostnameVerifier.verify(host, session)) return@HostnameVerifier true
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@HostnameVerifier false
        trust.check(authority, false, ServerCertTrustPolicy.FailureKind.HOSTNAME_MISMATCH, fingerprint(leaf))
    }
    return HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                sslSocketFactory(sslContext.socketFactory, tofu)
                hostnameVerifier(hostnameVerifier)
            }
        }
    }
}

private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

private fun fingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }

private fun classify(error: CertificateException, chain: Array<X509Certificate>): ServerCertTrustPolicy.FailureKind {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is CertificateExpiredException) return ServerCertTrustPolicy.FailureKind.EXPIRED
        cause = cause.cause
    }
    val leaf = chain.firstOrNull() ?: return ServerCertTrustPolicy.FailureKind.OTHER
    return if (chain.size == 1 && leaf.subjectX500Principal == leaf.issuerX500Principal) {
        ServerCertTrustPolicy.FailureKind.SELF_SIGNED
    } else {
        ServerCertTrustPolicy.FailureKind.UNTRUSTED_CHAIN
    }
}
