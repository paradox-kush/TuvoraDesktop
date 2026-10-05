package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.identity.M3uIdentity
import com.nuvio.app.features.iptv.identity.M3uIdentity.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * B64 — the ids an M3U entry is stored under. Regression: they were hashed from the RAW stream URL,
 * so a provider password change (`/live/alice/OLD/42.ts` -> `/live/alice/NEW/42.ts`) re-numbered every
 * channel and orphaned favourites / Continue Watching / watched marks. D2: a `/movie/` row named
 * "Show S01E02" is an episode (TV's promotion, ported), grouped with the show's `/series/` rows.
 */
class M3uIngestMappingTest {

    private fun entry(name: String, url: String, group: String? = "Group") =
        M3UParser.parseEntry("""#EXTINF:-1 tvg-id="" tvg-logo="" group-title="${group.orEmpty()}",$name""", url)!!

    private val old = Login("alice", "OLD")
    private val new = Login("alice", "NEW")

    @Test
    fun aPasswordChangeKeepsTheChannelId() {
        val before = M3uIngestMapping.map(entry("BBC One", "http://h.com/live/alice/OLD/42.ts"), old, 0)
        val after = M3uIngestMapping.map(entry("BBC One", "http://h.com/live/alice/NEW/42.ts"), new, 0)
        assertIs<M3uIngestRow.Channel>(before); assertIs<M3uIngestRow.Channel>(after)
        assertEquals(before.row.sid, after.row.sid)
        assertEquals(M3uIdentity.sidOf("http://h.com/live/42.ts"), after.row.sid)
    }

    @Test
    fun aPasswordChangeKeepsTheMovieId() {
        val before = M3uIngestMapping.map(entry("The Matrix (1999)", "http://h.com/movie/alice/OLD/7.mkv"), old, 0)
        val after = M3uIngestMapping.map(entry("The Matrix (1999)", "http://h.com/movie/alice/NEW/7.mkv"), new, 0)
        assertIs<M3uIngestRow.Movie>(before); assertIs<M3uIngestRow.Movie>(after)
        assertEquals(before.row.sid, after.row.sid)
    }

    @Test
    fun aPasswordChangeKeepsTheEpisodeId() {
        val before = M3uIngestMapping.map(entry("Show S01E02", "http://h.com/series/alice/OLD/9.mkv"), old, 0)
        val after = M3uIngestMapping.map(entry("Show S01E02", "http://h.com/series/alice/NEW/9.mkv"), new, 0)
        assertIs<M3uIngestRow.Episode>(before); assertIs<M3uIngestRow.Episode>(after)
        assertEquals(before.row.episodeId, after.row.episodeId)
        assertEquals(before.series.sid, after.series.sid)
    }

    @Test
    fun aUrlWithoutLoginKeepsItsPreB64Id() {
        val url = "https://cdn.example/streams/bbc-one.m3u8"
        val row = M3uIngestMapping.map(entry("BBC One", url), null, 0)
        assertIs<M3uIngestRow.Channel>(row)
        assertEquals(M3UClient.sidOf(url), row.row.sid)
    }

    @Test
    fun aMovieRowNamedLikeAnEpisodeIsPromotedToASeries() {
        val row = M3uIngestMapping.map(entry("Breaking Bad S01E02", "http://h.com/movie/alice/OLD/55.mp4", "Drama"), old, 0)
        assertIs<M3uIngestRow.Episode>(row)
        assertEquals(M3uIdentity.sidOf("series:breaking bad"), row.series.sid)
        assertEquals(1, row.row.season)
        assertEquals(2, row.row.episode)
        assertEquals(M3UClient.categoryId("Drama"), row.categoryId)
    }

    @Test
    fun aPromotedEpisodeJoinsTheSameSeriesAsTheSeriesRows() {
        val promoted = M3uIngestMapping.map(entry("Breaking Bad S01E02", "http://h.com/movie/a/b/55.mp4"), null, 0)
        val native = M3uIngestMapping.map(entry("Breaking Bad S01E03", "http://h.com/series/a/b/56.mkv"), null, 0)
        assertIs<M3uIngestRow.Episode>(promoted); assertIs<M3uIngestRow.Episode>(native)
        assertEquals(native.series.sid, promoted.series.sid)
    }

    @Test
    fun aGenuineMovieStaysAMovie() {
        assertIs<M3uIngestRow.Movie>(M3uIngestMapping.map(entry("2001: A Space Odyssey", "http://h.com/movie/a/b/1.mp4"), null, 0))
        assertIs<M3uIngestRow.Movie>(M3uIngestMapping.map(entry("S01E02", "http://h.com/movie/a/b/2.mp4"), null, 0))
    }

    @Test
    fun categoryIdsAreUnchanged() {
        val row = M3uIngestMapping.map(entry("BBC One", "http://h.com/live/a/b/1.ts", "UK News"), null, 0)
        assertEquals(M3UClient.sidOf("UK News").toString(), row.categoryId)
    }
}
