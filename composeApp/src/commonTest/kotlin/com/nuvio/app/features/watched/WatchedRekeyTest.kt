package com.nuvio.app.features.watched

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B64 device pass follow-up: a watched episode whose only change is its video id keeps its server row
 * (content id + season + episode). Deleting it as "old" while pushing it as "new" raced — the delete could
 * land second and drop the mark everywhere.
 */
class WatchedRekeyTest {

    private val series = "xtream:m3u|http://h/get.php|u00000000:series:5"

    private fun mark(videoId: String, id: String = series) =
        WatchedItem(id = id, type = "series", name = "Show", season = 1, episode = 2, videoId = videoId, markedAtEpochMs = 1)

    @Test
    fun aMarkThatKeepsItsServerRowIsNotDeleted() {
        assertTrue(WatchedRekey.serverDeletes(listOf(mark("old")), listOf(mark("new"))).isEmpty())
    }

    @Test
    fun aMarkThatMovesToAnotherRowIsDeleted() {
        val old = mark("old", id = "xtream:m3u|http://h/get.php|u00000000:vod:77")
        assertEquals(listOf(old), WatchedRekey.serverDeletes(listOf(old), listOf(mark("new"))))
    }
}
