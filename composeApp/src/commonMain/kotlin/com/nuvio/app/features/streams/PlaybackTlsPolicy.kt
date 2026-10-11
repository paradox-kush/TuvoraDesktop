package com.nuvio.app.features.streams

/**
 * How strictly the player validates TLS for a host (pure; the same shape TV ports).
 *
 *  - [Strict]: normal system validation, no fallback, ever. Media servers, add-on and catalog hosts, CDNs, unknown hosts.
 *  - [PermissivePanel]: the host belongs to one of the user's IPTV playlists/panels; after a failed handshake the request
 *    may be retried with certificate checks off, because panels commonly present self-signed or mismatched certificates.
 *
 * A host that is both a panel and a media server (the user pointed both at one box) stays [Strict]: a media server is never
 * unverified.
 */
internal enum class PlaybackTlsClass { Strict, PermissivePanel }

internal object PlaybackTlsPolicy {
    fun classify(
        host: String?,
        isPanelHost: (String) -> Boolean,
        isMediaServerHost: (String) -> Boolean,
    ): PlaybackTlsClass {
        val normalized = normalize(host) ?: return PlaybackTlsClass.Strict
        if (isMediaServerHost(normalized)) return PlaybackTlsClass.Strict
        return if (isPanelHost(normalized)) PlaybackTlsClass.PermissivePanel else PlaybackTlsClass.Strict
    }

    /** Lower case, no brackets, no trailing dot; null when nothing is left. */
    fun normalize(host: String?): String? =
        host?.trim()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
}
