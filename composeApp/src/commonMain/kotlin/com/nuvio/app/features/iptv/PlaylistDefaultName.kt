package com.nuvio.app.features.iptv

import io.ktor.http.decodeURLPart

/**
 * UX91 — the name an M3U-URL playlist gets when the user leaves "Name" empty.
 *
 * It used to be the URL's host, which is often the least telling part (a CDN, a bare IP, or a host
 * that has since died — the row then reads `dead.invalid`). The playlist FILE name is what the
 * provider chose to call the list ("uk_sports.m3u" → "uk sports"), so it wins whenever it says
 * something; generic names (`get.php`, `playlist.m3u`, a hash) fall back to the host as before.
 */
internal object PlaylistDefaultName {

    /** The file-name-derived name for [rawUrl], or null when the file name says nothing useful. */
    fun fromM3uUrl(rawUrl: String): String? {
        val afterScheme = rawUrl.trim().substringAfter("://", rawUrl.trim())
        val path = afterScheme.substringBefore('?').substringBefore('#')
        if (!path.contains('/')) return null   // host only — no file name at all
        val segment = path.substringAfter('/').trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { segment.decodeURLPart() }.getOrDefault(segment)
        val stem = decoded.substringBeforeLast('.').takeIf { it.isNotBlank() && decoded.substringAfterLast('.', "").lowercase() in KNOWN_EXTENSIONS }
            ?: decoded
        val name = stem.replace('_', ' ').trim()
        return name.takeIf { candidate ->
            candidate.isNotEmpty() &&
                candidate.length <= MAX_LENGTH &&
                candidate.any { it.isLetter() } &&
                candidate.lowercase() !in GENERIC_NAMES &&
                !looksLikeToken(candidate)
        }
    }

    /** A long run of hex/alphanumerics with no separators is a token, not a name ("a8f93c0d1e2b…"). */
    private fun looksLikeToken(name: String): Boolean =
        name.length >= 16 && name.none { it == ' ' || it == '-' } && name.count { it.isDigit() } >= 4

    private val KNOWN_EXTENSIONS = setOf("m3u", "m3u8", "txt", "php")
    private val GENERIC_NAMES = setOf(
        "get", "playlist", "index", "list", "iptv", "m3u", "m3u8", "tv", "channels", "download", "file",
        "player api", "playlist m3u", "live", "export",
    )
    private const val MAX_LENGTH = 60
}
