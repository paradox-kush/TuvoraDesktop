package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

/** F04: Continue Watching is one row by default; splitting into Movies and Series is a setting. */
class ContinueWatchingRowsPolicyTest {

    private data class Item(val id: String, val live: Boolean = false, val series: Boolean = false)

    private val items = listOf(
        Item("m1"), Item("s1", series = true), Item("live1", live = true, series = true), Item("m2"), Item("s2", series = true),
    )

    private fun rows(split: Boolean) =
        ContinueWatchingRowsPolicy.rows(items, splitByType = split, isLive = { it.live }, isSeries = { it.series })

    @Test
    fun by_default_movies_and_series_share_one_row_in_watch_order() {
        val rows = rows(split = false)
        assertEquals(1, rows.size)
        assertEquals(null, rows.single().title, "the default Continue Watching title")
        assertEquals(listOf("m1", "s1", "m2", "s2"), rows.single().items.map { it.id })
    }

    @Test
    fun split_gives_a_movies_row_then_a_series_row() {
        val rows = rows(split = true)
        assertEquals(listOf("Movies", "Series"), rows.map { it.title })
        assertEquals(listOf("m1", "m2"), rows[0].items.map { it.id })
        assertEquals(listOf("s1", "s2"), rows[1].items.map { it.id })
    }

    @Test
    fun live_channels_never_join_continue_watching() {
        (rows(false) + rows(true)).forEach { row -> assertEquals(false, row.items.any { it.live }, "${row.title}") }
    }

    @Test
    fun empty_rows_are_dropped() {
        val onlyMovies = ContinueWatchingRowsPolicy.rows(listOf(Item("m1")), splitByType = true, isLive = { it.live }, isSeries = { it.series })
        assertEquals(listOf("Movies"), onlyMovies.map { it.title })
        assertEquals(emptyList(), ContinueWatchingRowsPolicy.rows(emptyList<Item>(), false, { false }, { false }))
    }
}
