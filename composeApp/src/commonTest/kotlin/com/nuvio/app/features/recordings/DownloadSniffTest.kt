package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.DownloadSniff
import com.nuvio.app.features.recordings.internal.RecordingSizeSanity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadSniffTest {
    private fun b(s: String) = s.encodeToByteArray()

    @Test
    fun `an mpegurl content type is a playlist`() {
        assertTrue(DownloadSniff.isHlsPlaylist("application/vnd.apple.mpegurl", null))
        assertTrue(DownloadSniff.isHlsPlaylist("Audio/MPEGURL; charset=utf-8", b("whatever")))
        assertTrue(DownloadSniff.isHlsPlaylist("application/x-mpegURL", null))
    }

    @Test
    fun `a body starting with EXTM3U is a playlist whatever the content type says`() {
        assertTrue(DownloadSniff.isHlsPlaylist("text/plain", b("#EXTM3U\n#EXT-X-VERSION:3")))
        assertTrue(DownloadSniff.isHlsPlaylist(null, b("#EXTM3U")))
        assertTrue(DownloadSniff.isHlsPlaylist("application/octet-stream", b("#EXTM3U\n")))
    }

    @Test
    fun `a byte order mark and leading whitespace before EXTM3U are tolerated`() {
        assertTrue(DownloadSniff.isHlsPlaylist(null, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + b("#EXTM3U")))
        assertTrue(DownloadSniff.isHlsPlaylist(null, b("\n #EXTM3U")))
    }

    @Test
    fun `media is not a playlist`() {
        assertFalse(DownloadSniff.isHlsPlaylist("video/mp2t", byteArrayOf(0x47, 0x40, 0x00, 0x10)))
        assertFalse(DownloadSniff.isHlsPlaylist(null, null))
        assertFalse(DownloadSniff.isHlsPlaylist("video/mp4", b("#EXTM3")))
        assertFalse(DownloadSniff.isHlsPlaylist(null, ByteArray(0)))
    }

    @Test
    fun `size sanity flags under 64 KiB for a programme of a minute or more only`() {
        assertTrue(RecordingSizeSanity.implausiblySmall(1_024, 30 * 60_000L))
        assertTrue(RecordingSizeSanity.implausiblySmall(64L * 1024 - 1, 60_000L))
        assertFalse(RecordingSizeSanity.implausiblySmall(64L * 1024, 30 * 60_000L))
        assertFalse(RecordingSizeSanity.implausiblySmall(1_024, 59_999L))
        assertFalse(RecordingSizeSanity.implausiblySmall(1_024, 0L), "unknown length is never judged")
    }
}
