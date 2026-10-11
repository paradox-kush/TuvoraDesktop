package com.nuvio.app.features.servers

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Certificate trust for a media server: system validation FIRST; only when it FAILS may the user be offered
 * trust-on-first-use of the presented leaf certificate, pinned per `host:port`; a later mismatch is a CHANGED
 * certificate (hard failure, explicit re-trust). A publicly trusted chain is never pinned (Let's Encrypt rotates).
 * Pure: the platform glue (OkHttp trust manager, Darwin challenge handler, a JVM trust manager on Desktop) reports the
 * facts and enforces the verdict; this object is the decision. Shared by Mobile and Desktop.
 */
internal object ServerCertTrustPolicy {
    /** Why system validation failed - the platform maps its own error into one of these. */
    enum class FailureKind { UNTRUSTED_CHAIN, SELF_SIGNED, HOSTNAME_MISMATCH, EXPIRED, OTHER }

    sealed interface Verdict {
        data object Accept : Verdict

        /** System validation failed and nothing is pinned yet: ask whether to trust this certificate. */
        data class AskTrustNew(val fingerprint: String) : Verdict

        /** A different certificate than the pinned one: hard failure until the user accepts the new one. */
        data class AskCertificateChanged(val pinned: String, val presented: String) : Verdict

        /** A failure trust-on-first-use must not paper over (expired, or an unrelated TLS error). */
        data class Reject(val failure: FailureKind) : Verdict
    }

    /**
     * @param systemTrusted whether platform validation accepted the chain;
     * @param failure why it did not, when it did not;
     * @param presentedFingerprint the leaf's SHA-256 as the platform computes it (compared only with this device's own earlier value);
     * @param pinnedFingerprint what this device pinned for the host before, if anything;
     * @param host the address host, to judge whether a hostname mismatch is expected (a LAN IP).
     */
    fun decide(
        systemTrusted: Boolean,
        failure: FailureKind?,
        presentedFingerprint: String,
        pinnedFingerprint: String?,
        host: String,
    ): Verdict {
        if (systemTrusted) return Verdict.Accept
        val kind = failure ?: FailureKind.OTHER
        if (!canOfferTrust(kind, host)) return Verdict.Reject(kind)
        return when {
            pinnedFingerprint == null -> Verdict.AskTrustNew(presentedFingerprint)
            pinnedFingerprint == presentedFingerprint -> Verdict.Accept
            else -> Verdict.AskCertificateChanged(pinnedFingerprint, presentedFingerprint)
        }
    }

    /** Trust-on-first-use is for self-signed / private-CA certificates; a hostname mismatch qualifies only for a local or IP host; an expired certificate never does. */
    fun canOfferTrust(failure: FailureKind, host: String): Boolean = when (failure) {
        FailureKind.UNTRUSTED_CHAIN, FailureKind.SELF_SIGNED -> true
        FailureKind.HOSTNAME_MISMATCH -> isLocalHost(host) || isIpLiteral(host)
        FailureKind.EXPIRED, FailureKind.OTHER -> false
    }

    fun isLocalHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "::1") return true
        if (':' in h) return h.startsWith("fc") || h.startsWith("fd")
        val octets = h.split('.')
        if (octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 3 }) {
            val n = octets.map { it.toInt() }
            if (n.any { it > 255 }) return false
            return n[0] == 10 || n[0] == 127 || (n[0] == 172 && n[1] in 16..31) ||
                (n[0] == 192 && n[1] == 168) || (n[0] == 169 && n[1] == 254)
        }
        if ('.' !in h) return true
        return listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain").any { h.endsWith(it) }
    }

    fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (':' in h) return true
        val octets = h.split('.')
        return octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) }
    }
}

/** `host:port` (the scheme's default port filled in): the unit a pinned certificate is stored under. */
internal fun serverAuthority(url: String): String? {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return null
    if (parsed.protocol != URLProtocol.HTTP && parsed.protocol != URLProtocol.HTTPS) return null
    if (parsed.host.isBlank()) return null
    return "${parsed.host.lowercase()}:${parsed.port}"
}

/**
 * A certificate the platform refused. [changed] = this device had pinned a different one for the host.
 * Carries only public facts (the host and a certificate fingerprint), never a credential.
 */
internal class ServerCertificateException(
    val authority: String,
    val kind: String,
    val fingerprint: String,
    val changed: Boolean,
) : ServerException(
    if (changed) ServerFailure.CERTIFICATE_CHANGED else ServerFailure.CERTIFICATE_UNTRUSTED,
    "certificate for $authority not trusted ($kind)",
)

/** A handshake the trust glue refused, kept for the failing request to surface. */
internal data class TlsFailure(
    val authority: String,
    val kind: ServerCertTrustPolicy.FailureKind,
    val fingerprint: String,
    val pinned: String?,
    val atMs: Long = 0L,
)

/**
 * The certificates this device pinned, per `host:port`. A fingerprint is a public value but a fact about THIS device's
 * decision: it is stored in device-local storage and never enters the sync payload ([SyncedServer] has no field for it).
 */
internal class ServerPinStore(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val erase: () -> Unit,
) {
    private val lock = SynchronizedObject()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private var cache: Map<String, String>? = null

    fun pinned(authority: String): String? = synchronized(lock) { pins()[authority.lowercase()] }

    fun pin(authority: String, fingerprint: String) = synchronized(lock) {
        val next = pins() + (authority.lowercase() to fingerprint)
        save(json.encodeToString(serializer, next))
        cache = next
    }

    fun unpin(authority: String) = synchronized(lock) {
        val next = pins() - authority.lowercase()
        if (next.isEmpty()) erase() else save(json.encodeToString(serializer, next))
        cache = next
    }

    fun clearAll() = synchronized(lock) {
        erase()
        cache = emptyMap()
    }

    private fun pins(): Map<String, String> =
        cache ?: (load()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()).also { cache = it }
}

/**
 * The seam between the platform TLS stacks and [ServerCertTrustPolicy]: the platform reports the facts of one handshake
 * through [check] and enforces the answer. A refused handshake is remembered for [certificateException] so the failing
 * request can surface it; the UI shows the fingerprint and may [pin] it. Scoped by authority: a pin for one host:port
 * never applies to another.
 */
internal class ServerTrust(
    private val pins: ServerPinStore,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = SynchronizedObject()
    private val failures = mutableMapOf<String, TlsFailure>()

    /** True when the handshake may proceed: the system trusted the chain, or this exact certificate is pinned for [authority]. */
    fun check(
        authority: String,
        systemTrusted: Boolean,
        failure: ServerCertTrustPolicy.FailureKind?,
        fingerprint: String,
    ): Boolean {
        val host = authority.substringBeforeLast(':')
        val pinned = pins.pinned(authority)
        val verdict = ServerCertTrustPolicy.decide(systemTrusted, failure, fingerprint, pinned, host)
        if (verdict is ServerCertTrustPolicy.Verdict.Accept) {
            synchronized(lock) { failures.remove(authority.lowercase()) }
            return true
        }
        val kind = when (verdict) {
            is ServerCertTrustPolicy.Verdict.Reject -> verdict.failure
            else -> failure ?: ServerCertTrustPolicy.FailureKind.OTHER
        }
        synchronized(lock) { failures[authority.lowercase()] = TlsFailure(authority, kind, fingerprint, pinned, nowMs()) }
        return false
    }

    /**
     * The refused handshake for [authority] as the exception the UI branches on - only when trust may be offered for it. Not
     * consumed by reading: parallel requests (Home loads several libraries at once) all die on the same handshake and must all
     * report it. It ends when the host is accepted again, when the user pins, or after [REFUSAL_WINDOW_MS].
     */
    fun certificateException(authority: String): ServerCertificateException? {
        val f = synchronized(lock) { failures[authority.lowercase()] } ?: return null
        if (nowMs() - f.atMs > REFUSAL_WINDOW_MS) return null
        if (!ServerCertTrustPolicy.canOfferTrust(f.kind, authority.substringBeforeLast(':'))) return null
        return ServerCertificateException(f.authority, f.kind.name, f.fingerprint, changed = f.pinned != null && f.pinned != f.fingerprint)
    }

    /** A recent refused handshake exists for [authority] (offerable for trust or not) - the failing request died on the certificate, not the network. */
    fun hasRefusal(authority: String): Boolean {
        val f = synchronized(lock) { failures[authority.lowercase()] } ?: return false
        return nowMs() - f.atMs <= REFUSAL_WINDOW_MS
    }

    fun pinnedFingerprint(authority: String): String? = pins.pinned(authority)

    /** The user accepted this certificate. */
    fun pin(authority: String, fingerprint: String) {
        pins.pin(authority, fingerprint)
        forgetRefusal(authority)
    }

    fun unpin(authority: String) {
        pins.unpin(authority)
        forgetRefusal(authority)
    }

    private fun forgetRefusal(authority: String) = synchronized(lock) { failures.remove(authority.lowercase()) }

    /** Account wipe: every pin (they name the user's servers). */
    fun clearAll() = pins.clearAll()

    companion object {
        /** How long a refused handshake is still "the reason" for a failing request: one burst of parallel requests, not an hour. */
        const val REFUSAL_WINDOW_MS = 30_000L
        private const val PINS_KEY = "trust.pins"

        /** The device-wide instance, persisted in the device-local server storage (never synced). */
        val shared: ServerTrust by lazy {
            ServerTrust(
                ServerPinStore(
                    load = { runCatching { ServerStorage.read(PINS_KEY) }.getOrNull() },
                    save = { runCatching { ServerStorage.write(PINS_KEY, it) } },
                    erase = { runCatching { ServerStorage.write(PINS_KEY, null) } },
                ),
            )
        }
    }
}
