package com.nuvio.app.features.iptv

/** What the details header may show of a playlist's address: the host only, never credentials or a query. */
internal object PlaylistAddress {
    fun hostOnly(url: String): String? {
        val withoutScheme = url.trim().substringAfter("://", url.trim())
        val authority = withoutScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@')
        return host.takeIf { it.isNotEmpty() }
    }

    /** "2026-10-01" from an ISO-8601 timestamp, or null when [iso] is not one. */
    fun isoDate(iso: String?): String? {
        val d = iso?.trim()?.take(10) ?: return null
        val ok = d.length == 10 && d[4] == '-' && d[7] == '-' &&
            d.filterIndexed { i, _ -> i != 4 && i != 7 }.all { it in '0'..'9' }
        return d.takeIf { ok }
    }
}
