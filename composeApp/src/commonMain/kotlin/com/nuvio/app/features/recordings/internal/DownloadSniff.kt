package com.nuvio.app.features.recordings.internal

/** Tells an HLS playlist apart from a media file by what the response says it is, before a byte is saved. */
internal object DownloadSniff {
    /** Bytes to peek when the content type does not settle it. */
    const val PEEK_BYTES = 32

    /** True when the response is an HLS playlist: an `mpegurl` content type, or a body that starts with `#EXTM3U`. */
    fun isHlsPlaylist(contentType: String?, firstBytes: ByteArray?): Boolean {
        if (contentType?.contains("mpegurl", ignoreCase = true) == true) return true
        if (firstBytes == null) return false
        var i = 0
        // A UTF-8 byte order mark and leading whitespace are allowed before the tag.
        if (firstBytes.size >= 3 && firstBytes[0] == 0xEF.toByte() && firstBytes[1] == 0xBB.toByte() && firstBytes[2] == 0xBF.toByte()) i = 3
        while (i < firstBytes.size && (firstBytes[i] == ' '.code.toByte() || firstBytes[i] == '\n'.code.toByte() || firstBytes[i] == '\r'.code.toByte() || firstBytes[i] == '\t'.code.toByte())) i++
        val tag = EXTM3U
        if (firstBytes.size - i < tag.size) return false
        return tag.indices.all { firstBytes[i + it] == tag[it] }
    }

    private val EXTM3U = "#EXTM3U".encodeToByteArray()
}

/** A "finished" save that is far too small for the programme it claims to hold. */
internal object RecordingSizeSanity {
    const val MIN_PROGRAMME_MS = 60_000L
    const val MIN_BYTES = 64L * 1024

    /** True when [bytes] is under 64 KiB for a programme of at least 60 s: that is a stub or an error page, not footage. */
    fun implausiblySmall(bytes: Long, programmeMs: Long): Boolean =
        programmeMs >= MIN_PROGRAMME_MS && bytes < MIN_BYTES
}
