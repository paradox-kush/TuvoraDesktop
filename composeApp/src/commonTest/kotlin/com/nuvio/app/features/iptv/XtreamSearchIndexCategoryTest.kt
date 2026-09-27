package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.match.IptvSourceCategoryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B19: IPTV search honours the in-app category settings for movies and series (Xtream match index,
 * Stalker and M3U), not only for live channels, and filters before the per-type cap.
 */
class XtreamSearchIndexCategoryTest {

    private fun account(
        selections: CategorySelections = CategorySelections(),
        types: Set<String> = ALL_CONTENT_TYPES,
    ) = XtreamAccount(
        id = "acc", name = "P", baseUrl = "http://h", username = "u", password = "p",
        contentTypes = types, categorySelections = selections,
    )

    private fun movie(id: Int, category: String?) =
        XtreamMovie(streamId = id, name = "Matrix $id", poster = null, categoryId = category, rating = null, streamUrl = "")

    @Test
    fun `movie hits in a hidden category are not search results`() {
        val acc = account(CategorySelections(movies = listOf("10")))
        val hits = listOf(movie(1, "30"), movie(2, "10"))

        val shown = XtreamSearchIndex.offeredHits(acc, CONTENT_TYPE_MOVIES, hits) { it.categoryId }

        assertEquals(listOf(2), shown.map { it.streamId }, "allowed movie only")
    }

    @Test
    fun `hidden hits cannot crowd allowed ones out of the per-type cap`() {
        val acc = account(CategorySelections(series = listOf("10")))
        val hits = List(XtreamSearchIndex.PER_TYPE_CAP) { "hidden$it" to "30" } + listOf("ok" to "10")

        val shown = XtreamSearchIndex.offeredHits(acc, CONTENT_TYPE_SERIES, hits) { it.second }

        assertEquals(listOf("ok"), shown.map { it.first }, "the allowed series is still found")
    }

    @Test
    fun `a partial selection widens the local scan window`() {
        val partial = account(CategorySelections(movies = listOf("10")))

        assertTrue(
            XtreamSearchIndex.scanLimit(partial, CONTENT_TYPE_MOVIES) > XtreamSearchIndex.PER_TYPE_CAP,
            "a filtered search reads more rows than it shows",
        )
        assertEquals(IptvSourceCategoryPolicy.FILTERED_SCAN_LIMIT, XtreamSearchIndex.scanLimit(partial, CONTENT_TYPE_MOVIES), "wide window")
        assertEquals(XtreamSearchIndex.PER_TYPE_CAP, XtreamSearchIndex.scanLimit(account(), CONTENT_TYPE_MOVIES), "unfiltered reads the cap")
    }

    @Test
    fun `a switched-off or emptied type is not searched at all`() {
        assertFalse(account(types = setOf(CONTENT_TYPE_LIVE)).searchIncludesType(CONTENT_TYPE_MOVIES), "movies off")
        assertFalse(account(CategorySelections(series = emptyList())).searchIncludesType(CONTENT_TYPE_SERIES), "no series categories")
        assertTrue(account().searchIncludesType(CONTENT_TYPE_MOVIES), "default searches movies")
    }
}
