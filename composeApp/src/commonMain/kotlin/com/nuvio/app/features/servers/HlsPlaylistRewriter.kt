package com.nuvio.app.features.servers

/**
 * Rewrites a media playlist (HLS .m3u8) served by a pinned server so every reference that names the pinned server
 * itself - an absolute https URL of its authority, or a root-relative path - goes back through the relay. References to
 * other hosts are left alone (the player validates those normally); plain relative references already resolve against
 * the relay URL. Pure. The relay only buffers a playlist up to [MAX_PLAYLIST_BYTES]; anything bigger is refused rather
 * than streamed unrewritten (a segment list that large is not a real playlist).
 */
internal object HlsPlaylistRewriter {
    const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024

    fun isPlaylist(contentType: String?, path: String): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type == "application/vnd.apple.mpegurl" || type == "application/x-mpegurl" || type == "audio/mpegurl" || type == "audio/x-mpegurl") return true
        return path.substringBefore('?').lowercase().endsWith(".m3u8")
    }

    /**
     * @param authority the pinned `host:port`
     * @param relayBase `http://127.0.0.1:port/token` (no trailing slash)
     */
    fun rewrite(playlist: String, authority: String, relayBase: String): String =
        playlist.split("\n").joinToString("\n") { rawLine ->
            val line = rawLine.removeSuffix("\r")
            val cr = if (rawLine.endsWith("\r")) "\r" else ""
            when {
                line.isBlank() -> rawLine
                line.startsWith("#") -> rewriteTagUris(line, authority, relayBase) + cr
                else -> mapUri(line.trim(), authority, relayBase) + cr
            }
        }

    private val uriAttribute = Regex("""URI="([^"]*)"""")

    private fun rewriteTagUris(line: String, authority: String, relayBase: String): String =
        uriAttribute.replace(line) { match -> """URI="${mapUri(match.groupValues[1], authority, relayBase)}"""" }

    private fun mapUri(uri: String, authority: String, relayBase: String): String = when {
        uri.startsWith("https://", ignoreCase = true) && serverAuthority(uri) == authority.lowercase() ->
            relayBase + "/" + uri.substringAfter("://").substringAfter('/', "")
        uri.startsWith("/") && !uri.startsWith("//") -> relayBase + uri
        else -> uri
    }
}
