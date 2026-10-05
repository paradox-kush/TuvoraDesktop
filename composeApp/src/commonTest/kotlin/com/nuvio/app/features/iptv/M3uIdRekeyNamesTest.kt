package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.identity.M3uIdentity
import com.nuvio.app.features.iptv.identity.M3uIdentity.Login
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B64 device pass T2: items opened from Library / Continue Watching after the re-key had no title. A
 * promoted movie took its show's name from the catalog's series join, which reads "" on a miss — the
 * saved name was thrown away. A blank show name now keeps the saved one, and a blank catalog name on
 * the detail page falls through to the next candidate instead of showing nothing.
 */
class M3uIdRekeyNamesTest {

    private val p = "m3u|http://h.com/get.php?type=m3u_plus|u872213e7"
    private val login = Login("alice", "s3cret")
    private val url = "http://h.com/movie/alice/s3cret/55.mp4"
    private val oldVod = XtreamItemRegistry.vodId(p, M3uIdentity.sidOf(url))

    private fun plan(seriesName: String) = M3uIdRekey.plan(
        playlistId = p, login = login, channelUrls = emptyList(), vodUrls = emptyList(),
        episodes = listOf(M3uIdRekey.EpisodeLine(url, M3uIdentity.sidOf("series:breaking bad"), 1, 2, seriesName)),
    )

    @Test
    fun aBlankShowNameKeepsTheSavedLibraryName() {
        val moved = plan("").library(LibraryItem(id = oldVod, type = "movie", name = "Breaking Bad S01E02", savedAtEpochMs = 1))!!
        assertEquals("Breaking Bad S01E02", moved.name)
    }

    @Test
    fun aBlankShowNameKeepsTheSavedProgressTitle() {
        val entry = WatchProgressEntry(
            contentType = "movie", parentMetaId = oldVod, parentMetaType = "movie", videoId = oldVod, title = "Breaking Bad S01E02",
            lastPositionMs = 1, durationMs = 2, lastUpdatedEpochMs = 1,
        )
        assertEquals("Breaking Bad S01E02", plan(" ").progress(entry)!!.title)
    }

    @Test
    fun aBlankShowNameKeepsTheSavedWatchedName() {
        val mark = WatchedItem(id = oldVod, type = "movie", name = "Breaking Bad S01E02", markedAtEpochMs = 1)
        assertEquals("Breaking Bad S01E02", plan("").watched(mark)!!.name)
    }

    @Test
    fun aRealShowNameStillWins() {
        assertEquals("Breaking Bad", plan("Breaking Bad").library(LibraryItem(id = oldVod, type = "movie", name = "x", savedAtEpochMs = 1))!!.name)
    }

    @Test
    fun aBlankCatalogNameFallsThroughOnTheDetailPage() {
        assertEquals("Breaking Bad", xtreamMetaName("", "Breaking Bad", "Series"))
        assertEquals("Series", xtreamMetaName(" ", null, "Series"))
        assertEquals("Movie", xtreamMetaName(null, "", "Movie"))
        assertEquals("Heat", xtreamMetaName("Heat", "Other", "Movie"))
    }
}
