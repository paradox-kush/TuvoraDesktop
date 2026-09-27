package com.nuvio.app.features.iptv.match

import com.nuvio.app.features.iptv.ALL_CONTENT_TYPES
import com.nuvio.app.features.iptv.CONTENT_TYPE_LIVE
import com.nuvio.app.features.iptv.CONTENT_TYPE_SERIES
import com.nuvio.app.features.iptv.CategorySelections
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamMovie
import com.nuvio.app.features.iptv.XtreamSeriesItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B19: a title's IPTV source list honours the playlist's in-app content-type and category settings.
 * Exercises the candidate-selection seams of [XtreamStreamSource] (the object itself reaches TMDB,
 * the match index and the portal, so the decisions over already-fetched candidates are tested here).
 */
class XtreamStreamSourceCategoryTest {

    private fun account(
        selections: CategorySelections = CategorySelections(),
        types: Set<String> = ALL_CONTENT_TYPES,
        sourceType: String = "xtream",
    ) = XtreamAccount(
        id = "http://h|u", name = "P", baseUrl = "http://h", username = "u", password = "p",
        sourceType = sourceType, contentTypes = types, categorySelections = selections,
    )

    private fun item(sid: Int, name: String, category: String?) =
        IndexedItem(sid = sid, name = name, year = 1999, tmdb = 603, ext = "mkv", categoryId = category)

    @Test
    fun `a movie edition in a hidden category is not offered`() = runTest {
        val acc = account(CategorySelections(movies = listOf("10")))
        val hidden = item(1, "The Matrix (HIDDEN)", "30")
        val allowed = item(2, "The Matrix 4K", "10")

        val editions = XtreamStreamSource.movieEditions(acc, listOf(hidden, allowed)) { emptyList() }

        assertEquals(listOf(2), editions.map { it.sid }, "only the allowed edition is a source")
    }

    @Test
    fun `a hidden tmdb match falls back to allowed same-name editions`() = runTest {
        val acc = account(CategorySelections(movies = listOf("10")))
        val hidden = item(1, "The Matrix", "30")
        val sibling = item(2, "The Matrix", "10")

        val editions = XtreamStreamSource.movieEditions(acc, listOf(hidden)) { listOf(hidden, sibling) }

        assertEquals(listOf(2), editions.map { it.sid }, "the allowed sibling is offered")
    }

    @Test
    fun `a disabled content type offers no sources and no source group`() {
        val acc = account(types = setOf(CONTENT_TYPE_LIVE, CONTENT_TYPE_SERIES))

        assertFalse(XtreamStreamSource.offersKind(acc, MatchKind.MOVIE), "movies switched off: skip TMDB and provider work")
        assertTrue(XtreamStreamSource.offersKind(acc, MatchKind.SERIES), "series still on")
    }

    @Test
    fun `hidden series editions cannot crowd allowed ones out of the cap`() {
        val acc = account(CategorySelections(series = listOf("10")))
        // Five hidden editions ahead of three allowed: capping before filtering keeps only hidden ones.
        val entries = (1..5).map { item(it, "Show HIDDEN $it", "30") } + (6..8).map { item(it, "Show OK $it", "10") }

        val editions = XtreamStreamSource.seriesEditions(acc, entries, season = 1)

        assertEquals(listOf(6, 7, 8), editions.map { it.sid }, "the three allowed editions are offered")
    }

    @Test
    fun `series editions with no selection are only capped`() {
        val entries = (1..8).map { item(it, "Show $it", "30") }

        val editions = XtreamStreamSource.seriesEditions(account(), entries, season = 1)

        assertEquals(XtreamStreamSource.MAX_SERIES_EDITIONS, editions.size, "no selection filters nothing")
    }

    @Test
    fun `a Stalker movie in a hidden category is not offered`() {
        val acc = account(CategorySelections(movies = listOf("10")), sourceType = SOURCE_TYPE_STALKER)
        val results = listOf(
            XtreamMovie(streamId = 31, name = "The Matrix", poster = null, categoryId = "30", rating = null, streamUrl = ""),
            XtreamMovie(streamId = 11, name = "The Matrix", poster = null, categoryId = "10", rating = null, streamUrl = ""),
        )

        val editions = XtreamStreamSource.stalkerMovieEditions(acc, results, setOf(TitleNormalizer.normKey("The Matrix")), 1999)

        assertEquals(listOf(11), editions.map { it.streamId }, "one Stalker source: the allowed edition")
    }

    @Test
    fun `a Stalker series in a hidden category is not offered`() {
        val acc = account(CategorySelections(series = listOf("7")), sourceType = SOURCE_TYPE_STALKER)
        val results = List(5) {
            XtreamSeriesItem(seriesId = 100 + it, name = "Show", poster = null, categoryId = "30", plot = null, rating = null)
        } + XtreamSeriesItem(seriesId = 7, name = "Show", poster = null, categoryId = "7", plot = null, rating = null)

        val editions = XtreamStreamSource.stalkerSeriesEditions(acc, results, setOf(TitleNormalizer.normKey("Show")))

        assertEquals(listOf(7), editions.map { it.seriesId }, "the allowed edition survives past five hidden ones")
    }
}
