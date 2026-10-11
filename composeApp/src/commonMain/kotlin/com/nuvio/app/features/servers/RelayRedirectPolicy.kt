package com.nuvio.app.features.servers

import io.ktor.http.Url

/**
 * What the loopback relay does with a redirect from the pinned server (pure; shared with Desktop).
 *
 *  - back to the pinned authority (relative, root-relative, or absolute https naming it): served again THROUGH the relay;
 *  - to a public https host (an add-on instance's 302 to a debrid CDN): passed to the player as a bare redirect (Location only,
 *    none of our headers) - the player then validates THAT host normally, with its own certificate checks;
 *  - anything else (http downgrade, loopback / link-local / private / reserved address or name, numeric IP tricks, userinfo,
 *    malformed): refused (the relay answers 502). A redirect must never aim the player at the user's own network.
 */
internal object RelayRedirectPolicy {
    sealed interface Decision {
        /** Serve [path] (starts with "/") through the relay, same session. */
        data class ViaRelay(val path: String) : Decision

        /** Hand [location] (absolute https, public host) to the player unchanged. */
        data class PassThrough(val location: String) : Decision

        data object Refuse : Decision
    }

    fun isRedirect(status: Int): Boolean = status == 301 || status == 302 || status == 303 || status == 307 || status == 308

    fun decide(status: Int, location: String?, authority: String): Decision {
        if (!isRedirect(status) || location.isNullOrBlank()) return Decision.Refuse
        val target = location.trim()
        if (target.any { it.code < 0x21 || it.code == 0x7f }) return Decision.Refuse
        if (target.startsWith("//")) return Decision.Refuse
        if (target.startsWith("/")) return Decision.ViaRelay(target)
        if (!target.startsWith("https://", ignoreCase = true)) return Decision.Refuse
        val parsed = runCatching { Url(target) }.getOrNull() ?: return Decision.Refuse
        if (!parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty()) return Decision.Refuse
        if (serverAuthority(target) == authority.lowercase()) {
            val rest = target.substringAfter("://").substringAfter('/', "")
            return Decision.ViaRelay("/$rest")
        }
        return if (isPublicHost(parsed.host)) Decision.PassThrough(target) else Decision.Refuse
    }

    /** True for a name or address that is reachable on the public internet; false for anything local, private or reserved. */
    fun isPublicHost(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase().trimEnd('.')
        if (host.isEmpty() || '%' in host) return false
        if (host.startsWith("[") || ':' in host) return isPublicIpv6(host.removePrefix("[").removeSuffix("]"))
        val numeric = parseNumericIpv4(host)
        if (numeric != null) return isPublicIpv4(numeric)
        if (looksNumeric(host)) return false // an IPv4 spelling we could not parse: never guess
        if (host == "localhost" || host.endsWith(".localhost")) return false
        if ('.' !in host) return false
        if (privateSuffixes.any { host.endsWith(it) }) return false
        embeddedIpv4(host)?.let { if (!isPublicIpv4(it)) return false }
        return true
    }

    private val privateSuffixes = listOf(
        ".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".intranet", ".corp", ".private", ".test", ".invalid",
    )

    // Wildcard-DNS services that resolve "10.0.0.1.nip.io" / "10-0-0-1.sslip.io" to the embedded address: a rebinding disguise.
    private val wildcardDnsSuffixes = listOf("nip.io", "sslip.io", "xip.io", "traefik.me", "localtest.me", "lvh.me")

    private fun embeddedIpv4(host: String): Long? {
        val suffix = wildcardDnsSuffixes.firstOrNull { host == it || host.endsWith(".$it") } ?: return null
        if (suffix == "localtest.me" || suffix == "lvh.me") return 0x7f000001L
        val labels = host.removeSuffix(suffix).trimEnd('.').replace('-', '.').split('.')
        if (labels.size < 4) return null
        return parseNumericIpv4(labels.takeLast(4).joinToString("."))
    }

    private fun looksNumeric(host: String): Boolean = host.split('.').all { part ->
        part.isNotEmpty() && (part.all { it in '0'..'9' } || (part.startsWith("0x") && part.length > 2 && part.drop(2).all { it in '0'..'9' || it in 'a'..'f' }))
    }

    /** inet_aton spelling: 1-4 parts, decimal / 0-octal / 0x-hex, the last part filling the remaining bytes. Null if not that shape. */
    private fun parseNumericIpv4(host: String): Long? {
        if (!looksNumeric(host)) return null
        val parts = host.split('.')
        if (parts.size !in 1..4) return null
        val values = parts.map { part ->
            when {
                part.startsWith("0x") -> part.drop(2).toLongOrNull(16)
                part.length > 1 && part.startsWith("0") -> part.drop(1).toLongOrNull(8)
                else -> part.toLongOrNull(10)
            } ?: return null
        }
        var result = 0L
        for (i in 0 until values.size - 1) {
            if (values[i] !in 0..255) return null
            result = result or (values[i] shl (24 - 8 * i))
        }
        val last = values.last()
        val lastBits = 8 * (5 - values.size) // 32 for one part, 24 for two, 16 for three, 8 for four
        if (last < 0 || last >= (1L shl lastBits)) return null
        return result or last
    }

    private fun isPublicIpv4(v: Long): Boolean {
        fun inRange(base: Long, bits: Int): Boolean {
            val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            return (v and mask) == (base and mask)
        }
        val reserved = listOf(
            0x00000000L to 8, 0x0A000000L to 8, 0x64400000L to 10, 0x7F000000L to 8, 0xA9FE0000L to 16, 0xAC100000L to 12,
            0xC0000000L to 24, 0xC0000200L to 24, 0xC0A80000L to 16, 0xC6120000L to 15, 0xC6336400L to 24, 0xCB007100L to 24,
            0xE0000000L to 4, 0xF0000000L to 4,
        )
        return reserved.none { (base, bits) -> inRange(base, bits) }
    }

    private fun isPublicIpv6(text: String): Boolean {
        val groups = parseIpv6(text) ?: return false
        fun g(i: Int) = groups[i]
        val v4Tail = (g(6).toLong() shl 16) or g(7).toLong()
        if (groups.all { it == 0 }) return false // ::
        if (groups.take(7).all { it == 0 } && g(7) == 1) return false // ::1
        if (groups.take(5).all { it == 0 } && g(5) == 0xffff) return isPublicIpv4(v4Tail) // ::ffff:a.b.c.d
        if (groups.take(6).all { it == 0 }) return false // ::a.b.c.d (deprecated compatible)
        if (g(0) == 0x64 && g(1) == 0xff9b && groups.slice(2..5).all { it == 0 }) return isPublicIpv4(v4Tail) // NAT64
        if (g(0) == 0x2002) return isPublicIpv4((g(1).toLong() shl 16) or g(2).toLong()) // 6to4
        if ((g(0) and 0xfe00) == 0xfc00) return false // fc00::/7 unique local
        if ((g(0) and 0xffc0) == 0xfe80 || (g(0) and 0xffc0) == 0xfec0) return false // link-local, site-local
        if ((g(0) and 0xff00) == 0xff00) return false // multicast
        if (g(0) == 0x2001 && g(1) == 0x0db8) return false // documentation
        return true
    }

    private fun parseIpv6(text: String): List<Int>? {
        var s = text
        var tail: List<Int> = emptyList()
        if ('.' in s) {
            val v4 = parseNumericIpv4(s.substringAfterLast(':')) ?: return null
            tail = listOf(((v4 shr 16) and 0xffff).toInt(), (v4 and 0xffff).toInt())
            s = s.substringBeforeLast(':') + ":"
            if (s.endsWith("::")) Unit else s = s.removeSuffix(":")
        }
        val double = s.indexOf("::")
        if (double != s.lastIndexOf("::")) return null
        fun groupsOf(part: String): List<Int>? =
            if (part.isEmpty()) emptyList() else part.split(':').map { if (it.isEmpty() || it.length > 4) return null else it.toIntOrNull(16) ?: return null }
        val result = if (double >= 0) {
            val head = groupsOf(s.substring(0, double)) ?: return null
            val rest = groupsOf(s.substring(double + 2).removeSuffix(":")) ?: return null
            val fill = 8 - head.size - rest.size - tail.size
            if (fill < 1) return null
            head + List(fill) { 0 } + rest + tail
        } else {
            (groupsOf(s) ?: return null) + tail
        }
        return result.takeIf { it.size == 8 }
    }
}
