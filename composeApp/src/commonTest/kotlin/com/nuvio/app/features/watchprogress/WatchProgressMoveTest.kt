package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression (found during B64): a playlist key adoption copied each moved entry with its OLD progressKey,
 * so the server row kept the old content id — an M3U login included — as its identity (progress_key),
 * and the local entry stayed stored under the old key. A moved entry now re-derives its key.
 */
class WatchProgressMoveTest {

    private val oldSeries = "xtream:m3u|http://h/get.php?username=a&password=s3cret:series:5"
    private val newSeries = "xtream:m3u|http://h/get.php|u00000000:series:5"

    private fun episode() = WatchProgressEntry(
        contentType = "series", parentMetaId = oldSeries, parentMetaType = "series",
        videoId = "xtream:m3u|http://h/get.php?username=a&password=s3cret:episode:ab", title = "Show",
        seasonNumber = 1, episodeNumber = 2, lastPositionMs = 10, durationMs = 20, lastUpdatedEpochMs = 1,
        progressKey = "${oldSeries}_s1e2", lastSourceUrl = "http://h/series/a/s3cret/9.mkv",
    )

    @Test
    fun movedEpisodeRederivesItsKeyFromTheNewIds() {
        val moved = episode().movedTo(videoId = "xtream:m3u|http://h/get.php|u00000000:episode:ab", parentMetaId = newSeries)
        assertEquals("${newSeries}_s1e2", moved.resolvedProgressKey())
        assertFalse("s3cret" in moved.resolvedProgressKey())
        assertEquals(null, moved.lastSourceUrl)
        assertEquals(10L, moved.lastPositionMs)
    }

    @Test
    fun aPlainCopyKeepsTheStaleKey() {
        // The old behaviour, pinned so the helper is the only way a move is written.
        val copied = episode().copy(parentMetaId = newSeries)
        assertEquals("${oldSeries}_s1e2", copied.resolvedProgressKey())
    }
}
