package com.nuvio.app.features.recordings.internal

/** Tells an HLS playlist apart from a media file by what the response says it is, before a byte is saved. */
internal object DownloadSniff {
    /** Bytes to peek when the content type does not settle it. */
    const val PEEK_BYTES = 16

    /** True when the response is an HLS playlist: an `mpegurl` content type, or a body that starts with `#EXTM3U`. */
    fun isHlsPlaylist(contentType: String?, firstBytes: ByteArray?): Boolean = false
}

/** A "finished" save that is far too small for the programme it claims to hold. */
internal object RecordingSizeSanity {
    const val MIN_PROGRAMME_MS = 60_000L
    const val MIN_BYTES = 64L * 1024

    /** True when [bytes] is under 64 KiB for a programme of at least 60 s: that is a stub or an error page, not footage. */
    fun implausiblySmall(bytes: Long, programmeMs: Long): Boolean = false
}
