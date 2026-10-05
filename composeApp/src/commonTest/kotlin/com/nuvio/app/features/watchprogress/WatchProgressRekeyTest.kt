package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B64 device pass T1 (Continue Watching showed one episode twice after the re-key). A re-key that meets
 * the moved entry already stored (an old-id row a remote pull brought back after the first re-key) must
 * leave ONE entry with the newest progress, and must never delete on the server a row it still holds.
 */
class WatchProgressRekeyTest {

    private val series = "xtream:m3u|http://h/get.php|u00000000:series:5"
    private val oldVod = "xtream:m3u|http://h/get.php|u00000000:vod:77"
    private val newEpisode = "xtream:m3u|http://h/get.php|u00000000:episode:ab"
    private val oldEpisode = "xtream:m3u|http://h/get.php|u00000000:episode:cd"

    private fun episode(videoId: String, position: Long, updated: Long) = WatchProgressEntry(
        contentType = "series", parentMetaId = series, parentMetaType = "series", videoId = videoId, title = "Show",
        seasonNumber = 1, episodeNumber = 2, lastPositionMs = position, durationMs = 1_000_000, lastUpdatedEpochMs = updated,
    ).withResolvedProgressKey()

    /** The plan's promotion of the old movie row: the episode under the show. */
    private val promote: (WatchProgressEntry) -> WatchProgressEntry? = { e ->
        if (e.videoId != oldVod) null else e.copy(
            contentType = "series", parentMetaId = series, parentMetaType = "series", videoId = newEpisode,
            seasonNumber = 1, episodeNumber = 2, progressKey = null,
        )
    }

    @Test
    fun anOldIdRowPulledBackAfterTheRekeyCollapsesIntoTheNewerEntry() {
        val moved = episode(newEpisode, position = 500_000, updated = 2_000)
        val pulledBack = WatchProgressEntry(
            contentType = "movie", parentMetaId = oldVod, parentMetaType = "movie", videoId = oldVod, title = "Show S01E02",
            lastPositionMs = 100_000, durationMs = 1_000_000, lastUpdatedEpochMs = 1_000, progressKey = oldVod,
        )
        val plan = WatchProgressRekey.plan(listOf(moved, pulledBack), promote)
        assertEquals(listOf(oldVod), plan.removed.map { it.resolvedProgressKey() }, "the old-id row leaves")
        assertTrue(plan.upserts.isEmpty(), "the stored entry is newer: the stale pulled-back copy must not overwrite it")
        assertEquals(listOf(oldVod), plan.serverDeletes.map { it.resolvedProgressKey() }, "the old row is deleted on the server")
    }

    @Test
    fun aNewerOldIdRowStillWins() {
        val moved = episode(newEpisode, position = 100_000, updated = 1_000)
        val pulledBack = WatchProgressEntry(
            contentType = "movie", parentMetaId = oldVod, parentMetaType = "movie", videoId = oldVod, title = "Show S01E02",
            lastPositionMs = 700_000, durationMs = 1_000_000, lastUpdatedEpochMs = 3_000, progressKey = oldVod,
        )
        val plan = WatchProgressRekey.plan(listOf(moved, pulledBack), promote)
        assertEquals(1, plan.upserts.size)
        assertEquals(700_000L, plan.upserts.single().lastPositionMs, "watched further on the other device: that progress is kept")
        assertEquals("${series}_s1e2", plan.upserts.single().resolvedProgressKey())
    }

    @Test
    fun anEpisodeRenamedUnderTheSameKeyIsNotDeletedOnTheServer() {
        // An episode's storage key is {series}_s{S}e{E}: renaming only its video id keeps the key, so a
        // server delete of the "old" row would delete the moved one (the delete and the push race).
        val old = episode(oldEpisode, position = 300_000, updated = 1_000)
        val plan = WatchProgressRekey.plan(listOf(old)) { e -> if (e.videoId == oldEpisode) e.copy(videoId = newEpisode) else null }
        assertEquals(listOf(newEpisode), plan.upserts.map { it.videoId })
        assertTrue(plan.serverDeletes.isEmpty(), "same progress key before and after: nothing to delete on the server")
    }
}
