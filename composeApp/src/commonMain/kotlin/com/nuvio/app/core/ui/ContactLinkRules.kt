package com.nuvio.app.core.ui

/**
 * Shape rules for provider-supplied contact links (security L10). Pure Kotlin on purpose: it lives in core (so
 * the fork feature may use it) and compiles for tvOS, unlike [ExternalLinkPolicy] which imports Compose.
 */
internal object ContactLinkRules {
    private val emailShape = Regex("""^[A-Za-z0-9.!#$'*+/=_~-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$""")

    /** A plain address (no `?` headers, quotes or brackets): the only shape a `mailto:` link is built from. */
    fun isPlainEmailAddress(value: String): Boolean = value.length <= 254 && emailShape.matches(value)

    /**
     * `host` or `host:port` of a public-looking host: ASCII letters, digits and hyphens in at least two
     * dot-separated labels with an alphabetic top level, an optional numeric port (1-65535). IP literals (v4 and
     * v6), single-label hosts (localhost, intranet names), non-ASCII and punycode (`xn--`, IDN) hosts are refused.
     */
    fun isPublicLookingAuthority(authority: String): Boolean {
        val host = authority.substringBefore(':')
        if (':' in authority) {
            val port = authority.substringAfter(':')
            if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' } || port.toInt() !in 1..65535) return false
        }
        if (host.isEmpty() || host.length > 253) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith("-") || label.endsWith("-")) return false
            if (!label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }) return false
            if (label.startsWith("xn--", ignoreCase = true)) return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it in 'a'..'z' || it in 'A'..'Z' }
    }

    /**
     * A provider-supplied contact link may be opened only when it is `https://` on a public-looking host or a
     * plain `mailto:` address (no `?` headers): the text is a provider's, so the opener is never handed
     * `javascript:`, `intent:`, `file:`, `tel:`, a custom scheme, plain http, an IP literal or an intranet name
     * (security L10). Other app links keep using [open], unchanged.
     */
    fun isSafe(url: String): Boolean {
        val s = url.trim()
        if (s.isEmpty() || s.any { it.code <= 0x20 || it.code in 0x7f..0x9f || it in "<>\"\\^`|" }) return false
        if (s.startsWith("mailto:", ignoreCase = true)) {
            val address = s.substring("mailto:".length)
            return address.isNotEmpty() && '?' !in address && ',' !in address && ';' !in address && '%' !in address &&
                address.count { it == '@' } == 1 && isPlainEmailAddress(address)
        }
        if (!s.startsWith("https://", ignoreCase = true)) return false
        val authority = s.substring("https://".length).takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.isNotEmpty() && '@' !in authority && '%' !in authority && isPublicLookingAuthority(authority)
    }
}
