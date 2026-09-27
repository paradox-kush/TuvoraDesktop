package com.nuvio.app.features.iptv.match

import com.nuvio.app.features.iptv.ALL_CONTENT_TYPES
import com.nuvio.app.features.iptv.CONTENT_TYPE_LIVE
import com.nuvio.app.features.iptv.CONTENT_TYPE_MOVIES
import com.nuvio.app.features.iptv.CONTENT_TYPE_SERIES
import com.nuvio.app.features.iptv.CategorySelections
import com.nuvio.app.features.iptv.XtreamAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B19: the in-app category settings decide what a playlist may offer as a source or search hit. */
class IptvSourceCategoryPolicyTest {

    private data class Item(val name: String, val categoryId: String?)

    private fun account(
        selections: CategorySelections = CategorySelections(),
        types: Set<String> = ALL_CONTENT_TYPES,
    ) = XtreamAccount(
        id = "http://h|u", name = "P", baseUrl = "http://h", username = "u", password = "p",
        contentTypes = types, categorySelections = selections,
    )

    private val movies = CONTENT_TYPE_MOVIES

    @Test
    fun `a title in a hidden category is not offered`() {
        val acc = account(CategorySelections(movies = listOf("10")))
        val kept = IptvSourceCategoryPolicy.keep(
            acc, movies, listOf(Item("Allowed", "10"), Item("Hidden", "30")),
        ) { it.categoryId }
        assertEquals(listOf("Allowed"), kept.map { it.name }, "only the allowed category survives")
    }

    @Test
    fun `a disabled content type offers nothing`() {
        val acc = account(types = setOf(CONTENT_TYPE_LIVE, CONTENT_TYPE_SERIES))
        assertFalse(IptvSourceCategoryPolicy.offers(acc, movies), "movies switched off")
        val kept = IptvSourceCategoryPolicy.keep(acc, movies, listOf(Item("Any", "10"))) { it.categoryId }
        assertTrue(kept.isEmpty(), "no movie is offered")
    }

    @Test
    fun `an explicit empty selection offers nothing`() {
        val acc = account(CategorySelections(movies = emptyList()))
        assertFalse(IptvSourceCategoryPolicy.offers(acc, movies), "'Deselect All' means none")
        assertTrue(
            IptvSourceCategoryPolicy.keep(acc, movies, listOf(Item("Any", "10"))) { it.categoryId }.isEmpty(),
            "no movie is offered",
        )
    }

    @Test
    fun `no selection offers everything including uncategorised items`() {
        val acc = account()
        assertTrue(IptvSourceCategoryPolicy.offers(acc, movies), "default playlist offers movies")
        val items = listOf(Item("A", "10"), Item("B", null), Item("C", "brand-new"))
        assertEquals(items, IptvSourceCategoryPolicy.keep(acc, movies, items) { it.categoryId }, "all kept and order preserved")
    }

    @Test
    fun `an uncategorised item is dropped under a partial selection`() {
        val acc = account(CategorySelections(movies = listOf("10")))
        val kept = IptvSourceCategoryPolicy.keep(acc, movies, listOf(Item("NoCat", null), Item("A", "10"))) { it.categoryId }
        assertEquals(listOf("A"), kept.map { it.name }, "same rule as browse and live search")
    }

    @Test
    fun `the cap applies after filtering`() {
        val acc = account(CategorySelections(movies = listOf("10")))
        // Hidden items first: capping before filtering would leave nothing.
        val items = List(5) { Item("hidden$it", "30") } + List(4) { Item("ok$it", "10") }
        val kept = IptvSourceCategoryPolicy.keepCapped(acc, movies, items, cap = 3) { it.categoryId }
        assertEquals(listOf("ok0", "ok1", "ok2"), kept.map { it.name }, "three allowed items fill the cap")
    }

    @Test
    fun `selections are per type`() {
        val acc = account(CategorySelections(series = listOf("5")))
        val kept = IptvSourceCategoryPolicy.keep(acc, movies, listOf(Item("A", "30"))) { it.categoryId }
        assertEquals(listOf("A"), kept.map { it.name }, "a series selection does not filter movies")
    }

    @Test
    fun `scan limit widens only under a partial selection`() {
        assertEquals(60, IptvSourceCategoryPolicy.scanLimit(account(), movies, 60), "no selection reads the cap")
        assertEquals(
            IptvSourceCategoryPolicy.FILTERED_SCAN_LIMIT,
            IptvSourceCategoryPolicy.scanLimit(account(CategorySelections(movies = listOf("1"))), movies, 60),
            "partial selection reads the wider window",
        )
    }

    @Test
    fun `kinds map to playlist content types`() {
        assertEquals(CONTENT_TYPE_MOVIES, IptvSourceCategoryPolicy.typeOf(MatchKind.MOVIE), "movie")
        assertEquals(CONTENT_TYPE_SERIES, IptvSourceCategoryPolicy.typeOf(MatchKind.SERIES), "series")
        assertEquals(CONTENT_TYPE_LIVE, IptvSourceCategoryPolicy.typeOf(MatchKind.LIVE), "live")
    }
}
