package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.identity.M3uIdentity
import com.nuvio.app.features.iptv.identity.M3uIdentity.Login
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B64 phase 3 — the pure re-key of saved M3U ids (see [M3uIdRekey]). */
class M3uIdRekeyTest {

    private val p = "m3u|http://h.com/get.php?type=m3u_plus|u872213e7"
    private val login = Login("alice", "s3cret")
    private val liveUrl = "http://h.com/live/alice/s3cret/42.ts"
    private val vodUrl = "http://h.com/movie/alice/s3cret/7.mkv"
    private val promotedUrl = "http://h.com/movie/alice/s3cret/55.mp4"
    private val seriesUrl = "http://h.com/series/alice/s3cret/9.mkv"
    private val cleanUrl = "https://cdn.example/bbc.m3u8"
    private val bbSid = M3uIdentity.sidOf("series:breaking bad")

    private fun plan() = M3uIdRekey.plan(
        playlistId = p,
        login = login,
        channelUrls = listOf(liveUrl, cleanUrl),
        vodUrls = listOf(vodUrl),
        episodes = listOf(
            M3uIdRekey.EpisodeLine(promotedUrl, bbSid, 1, 2, "Breaking Bad"),
            M3uIdRekey.EpisodeLine(seriesUrl, bbSid, 1, 3, "Breaking Bad"),
        ),
    )

    private fun oldLive(url: String) = XtreamItemRegistry.liveId(p, M3uIdentity.sidOf(url))
    private fun newLive(url: String) = XtreamItemRegistry.liveId(p, M3uIdentity.itemSid(url, login))

    @Test
    fun renamesOnlyLoginBearingIds() {
        val plan = plan()
        assertEquals(newLive(liveUrl), plan.contentId(oldLive(liveUrl)))
        assertNull(plan.contentId(oldLive(cleanUrl)), "a URL without a login keeps its id: not in the plan")
        assertEquals(
            XtreamItemRegistry.vodId(p, M3uIdentity.itemSid(vodUrl, login)),
            plan.contentId(XtreamItemRegistry.vodId(p, M3uIdentity.sidOf(vodUrl))),
        )
        assertEquals(
            XtreamItemRegistry.episodeId(p, M3uIdentity.episodeId(seriesUrl, login)),
            plan.contentId(XtreamItemRegistry.episodeId(p, M3uIdentity.sidOf(seriesUrl).toString(16))),
        )
    }

    @Test
    fun aFavouriteChannelFollowsItsNewId() {
        val fav = LibraryItem(id = oldLive(liveUrl), type = "tv", name = "BBC One", savedAtEpochMs = 1)
        assertEquals(newLive(liveUrl), plan().library(fav)?.id)
        assertNull(plan().library(LibraryItem(id = "tt0111161", type = "movie", name = "Not IPTV", savedAtEpochMs = 1)))
    }

    @Test
    fun aSavedPromotedMovieBecomesItsShow() {
        val oldVod = XtreamItemRegistry.vodId(p, M3uIdentity.sidOf(promotedUrl))
        val moved = plan().library(LibraryItem(id = oldVod, type = "movie", name = "Breaking Bad S01E02", savedAtEpochMs = 1))!!
        assertEquals(XtreamItemRegistry.seriesId(p, bbSid), moved.id)
        assertEquals("series", moved.type)
        assertEquals("Breaking Bad", moved.name)
    }

    @Test
    fun promotedMovieProgressBecomesEpisodeProgressWithAFreshKey() {
        val oldVod = XtreamItemRegistry.vodId(p, M3uIdentity.sidOf(promotedUrl))
        val entry = WatchProgressEntry(
            contentType = "movie", parentMetaId = oldVod, parentMetaType = "movie", videoId = oldVod,
            title = "Breaking Bad S01E02", lastPositionMs = 60_000, durationMs = 3_000_000, lastUpdatedEpochMs = 1,
            progressKey = oldVod,
        )
        val moved = plan().progress(entry)!!
        assertEquals(XtreamItemRegistry.seriesId(p, bbSid), moved.parentMetaId)
        assertEquals(XtreamItemRegistry.episodeId(p, M3uIdentity.episodeId(promotedUrl, login)), moved.videoId)
        assertEquals(1, moved.seasonNumber); assertEquals(2, moved.episodeNumber)
        assertEquals(60_000, moved.lastPositionMs)
        assertNull(moved.progressKey, "re-derived from the new ids, never the old login-bearing one")
    }

    @Test
    fun episodeProgressAndWatchedMarksFollowTheEpisodeId() {
        val oldEp = XtreamItemRegistry.episodeId(p, M3uIdentity.sidOf(seriesUrl).toString(16))
        val series = XtreamItemRegistry.seriesId(p, bbSid)
        val entry = WatchProgressEntry(
            contentType = "series", parentMetaId = series, parentMetaType = "series", videoId = oldEp,
            title = "Breaking Bad", seasonNumber = 1, episodeNumber = 3, lastPositionMs = 1, durationMs = 2,
            lastUpdatedEpochMs = 1, progressKey = "${series}_s1e3",
        )
        val newEp = XtreamItemRegistry.episodeId(p, M3uIdentity.episodeId(seriesUrl, login))
        assertEquals(newEp, plan().progress(entry)?.videoId)
        val mark = WatchedItem(id = series, type = "series", name = "Breaking Bad", season = 1, episode = 3, videoId = oldEp, markedAtEpochMs = 1)
        assertEquals(newEp, plan().watched(mark)?.videoId)
    }

    @Test
    fun applyingThePlanTwiceIsANoOp() {
        val plan = plan()
        val once = plan.library(LibraryItem(id = oldLive(liveUrl), type = "tv", name = "BBC One", savedAtEpochMs = 1))!!
        assertNull(plan.library(once))
        assertTrue(plan.renames.values.none { it in plan.renames.keys })
    }

    @Test
    fun aPlaylistWithoutLoginsOnlyPromotes() {
        val plan = M3uIdRekey.plan(p, null, listOf(cleanUrl), listOf("https://cdn.example/film.mp4"), emptyList())
        assertTrue(plan.isEmpty)
    }
}
