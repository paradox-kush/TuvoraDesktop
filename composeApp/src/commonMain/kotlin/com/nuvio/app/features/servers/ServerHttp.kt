package com.nuvio.app.features.servers

import io.ktor.client.HttpClient
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Where a server request is sent from. One implementation per client strategy: [FixedServerHttp] (a single client - tests)
 * and [TrustedServerHttp] (one TLS-aware client per server authority, so trust is scoped to that host only).
 */
internal interface ServerHttp {
    fun clientFor(url: String): HttpClient

    /** The certificate refusal recorded for [url]'s host while its request was failing, if any (cleared by reading). */
    fun certificateFailure(url: String): ServerCertificateException? = null

    /** Whether a handshake for [url]'s host was refused by the trust glue (even one that cannot be offered for trust, like an expired certificate). */
    fun certificateRefused(url: String): Boolean = false
}

internal class FixedServerHttp(private val client: HttpClient) : ServerHttp {
    override fun clientFor(url: String): HttpClient = client
}

/**
 * One client per `host:port`, created on first use, so each client's TLS glue knows which host it validates and a
 * certificate decision at one server can never affect another. NEVER a trust-all client: only a certificate this
 * device pinned for that exact authority is accepted when system validation fails.
 */
internal class TrustedServerHttp(
    private val trust: ServerTrust,
    private val factory: (authority: String, trust: ServerTrust) -> HttpClient = ::createTrustedServerHttpClient,
) : ServerHttp {
    private val lock = SynchronizedObject()
    private val clients = mutableMapOf<String, HttpClient>()

    override fun clientFor(url: String): HttpClient {
        val authority = serverAuthority(url) ?: return synchronized(lock) { clients.getOrPut("") { factory("", trust) } }
        return synchronized(lock) { clients.getOrPut(authority) { factory(authority, trust) } }
    }

    override fun certificateFailure(url: String): ServerCertificateException? =
        serverAuthority(url)?.let(trust::certificateException)

    override fun certificateRefused(url: String): Boolean =
        serverAuthority(url)?.let(trust::hasRefusal) == true

    companion object {
        val shared: TrustedServerHttp by lazy { TrustedServerHttp(ServerTrust.shared) }
    }
}

/** One HTTP client for [authority] (`host:port`) whose TLS glue consults [trust]. Platform-specific (OkHttp / Darwin / a JVM trust manager on Desktop). */
internal expect fun createTrustedServerHttpClient(authority: String, trust: ServerTrust): HttpClient
