package com.nuvio.app.features.iptv

import com.nuvio.app.core.diag.LogRedaction

/** What a playlist's address and name may SHOW: never its credentials (the host only, or the login masked). */
internal object PlaylistAddress {
    fun hostOnly(url: String): String? {
        val withoutScheme = url.trim().substringAfter("://", url.trim())
        val authority = withoutScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@')
        return host.takeIf { it.isNotEmpty() }
    }

    /**
     * P4/T7 — a playlist address as a READ-ONLY row shows it: the login masked by the B116 redaction
     * ([LogRedaction.url] — `username=MASK&password=MASK`, `MASK@host`, Xtream `live/MASK/MASK/…` paths), scheme, host
     * and path shape kept so lists on one panel stay tellable apart. Editable fields keep the raw value.
     */
    fun masked(url: String): String = LogRedaction.url(url)

    /**
     * P4/T7 — the name a synced row WITHOUT one gets. It used to be the raw address, so an M3U link's
     * `username=…&password=…` became the playlist's name in every list, picker and dropdown — and
     * synced back. Now the add form's rule (an M3U link's telling file name, else the host), never a login.
     */
    fun fallbackName(address: String, isM3uLink: Boolean = false): String =
        (if (isM3uLink) PlaylistDefaultName.fromM3uUrl(address) else null) ?: hostOnly(address) ?: masked(address)

    /**
     * P4/T7 — a playlist NAME as lists and pickers show it. Names older builds gave nameless synced rows
     * ARE the raw address (and were pushed to the server), so a name that is an address shows as its
     * host, and an address inside a name is masked. Ordinary names pass through untouched.
     */
    fun displayName(name: String): String {
        if ("://" !in name) return name
        val trimmed = name.trim()
        val wholeAddress = trimmed.none { it.isWhitespace() } && trimmed.indexOf("://") > 0
        return if (wholeAddress) hostOnly(trimmed) ?: masked(trimmed) else LogRedaction.text(name)
    }

    /** "2026-10-01" from an ISO-8601 timestamp, or null when [iso] is not one. */
    fun isoDate(iso: String?): String? {
        val d = iso?.trim()?.take(10) ?: return null
        val ok = d.length == 10 && d[4] == '-' && d[7] == '-' &&
            d.filterIndexed { i, _ -> i != 4 && i != 7 }.all { it in '0'..'9' }
        return d.takeIf { ok }
    }
}
