package com.nuvio.app.features.servers

/** The request could not even reach the server (refused, timed out, not speaking TLS): the only failure that may fall back to plain http. */
internal class ServerConnectException : ServerException(ServerFailure.UNREACHABLE)

/** A TLS handshake the trust glue refused that cannot be offered for trust (an expired certificate): never a reason to fall back to http. */
internal class ServerCertificateRefusedException : ServerException(ServerFailure.CERTIFICATE_UNTRUSTED)

/**
 * An address typed without a scheme did not answer over https but does answer over plain http. Nothing secret has been sent:
 * the screen asks the user, and only an explicit "continue over http" retries with [httpAddress].
 */
internal class ServerPlainHttpException(val httpAddress: String) : ServerException(ServerFailure.UNREACHABLE)

/**
 * Which URLs a typed server address is tried at, and when plain http may be used. Pure.
 *
 * An explicit scheme is honoured as typed. Without one the address is tried over https FIRST - always, whatever the port: the
 * Jellyfin / Emby defaults (http 8096, https 8920) are conventions, not a licence to skip https, so a user who types
 * "host:8096" still gets the https attempt (it fails to connect, then the screen asks before plain http). A fallback to http
 * happens only when https failed to CONNECT; a certificate error goes to the trust prompt and a server that answered with an error
 * stays on https. The fallback is never silent and never carries a secret: [attempts] only feeds the unauthenticated probe.
 */
internal object ServerAddressPolicy {
    enum class Outcome { CONNECT_FAILED, CERTIFICATE, SERVER_ANSWERED }

    fun attempts(input: String): List<String> {
        val value = input.trim()
        if (value.isEmpty()) return emptyList()
        return if (value.contains("://")) listOf(value) else listOf("https://$value", "http://$value")
    }

    fun hasExplicitScheme(input: String): Boolean = input.trim().contains("://")

    fun fallsBackToPlainHttp(outcome: Outcome): Boolean = outcome == Outcome.CONNECT_FAILED

    fun outcomeOf(error: Throwable): Outcome = when (error) {
        is ServerCertificateException, is ServerCertificateRefusedException -> Outcome.CERTIFICATE
        is ServerConnectException -> Outcome.CONNECT_FAILED
        else -> Outcome.SERVER_ANSWERED
    }

    fun plainHttpOf(httpsUrl: String): String? =
        if (httpsUrl.startsWith("https://", ignoreCase = true)) "http://" + httpsUrl.substring("https://".length) else null
}

/** Which id this device presents to a Jellyfin / Emby server (tokens are bound to it). Pure. */
internal object ServerDeviceIdPolicy {
    private const val NEW_PREFIX = "tuvora-mobile-"
    private val sharedPrefixes = listOf("nuvio-mobile-", "tuvora-mobile-")

    /**
     * A stored id always wins. A device that already has signed-in servers keeps the shared client id it signed in with, so
     * their tokens stay valid; only a device with none gets a Tuvora-prefixed id. (The shared sync client id keeps its
     * `nuvio-mobile-` prefix: the backend classifies the platform by it.)
     */
    fun choose(stored: String?, sharedClientId: String, hasSignedInServers: Boolean): String {
        stored?.takeIf { it.isNotBlank() }?.let { return it }
        if (hasSignedInServers) return sharedClientId
        val suffix = sharedPrefixes.firstOrNull { sharedClientId.startsWith(it) }?.let { sharedClientId.removePrefix(it) } ?: sharedClientId
        return NEW_PREFIX + suffix
    }
}

internal object ServerDeviceId {
    private const val KEY = "device.id"
    private var cached: String? = null

    fun current(): String {
        cached?.let { return it }
        val stored = runCatching { ServerStorage.read(KEY) }.getOrNull()
        val chosen = ServerDeviceIdPolicy.choose(
            stored = stored,
            sharedClientId = com.nuvio.app.core.sync.SyncClientIdentity.currentClientId(),
            hasSignedInServers = ServerRepository.uiState.value.connections.isNotEmpty(),
        )
        if (stored != chosen) runCatching { ServerStorage.write(KEY, chosen) }
        cached = chosen
        return chosen
    }
}
