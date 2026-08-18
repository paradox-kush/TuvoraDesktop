package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression cover for the 2026-08-17 field report ("EPG takes a very long time to load, had to
 * abandon it"). The old guide asked the panel for a row whenever that row COMPOSED, so a fling
 * through a 10,000-channel lineup issued one `get_short_epg` per row that flew past: measured on
 * the emulator at 412 requests / 390 concurrent connections from eight flings.
 *
 * These pin the two properties that make that impossible: the ask is bounded by the visible
 * range (not the scroll distance), and the visible rows resolve before their neighbours.
 */
class GuideEpgPrefetchPolicyTest {

    @Test
    fun `visible rows come first - in reading order`() {
        val window = GuideEpgPrefetchPolicy.windowFor(firstVisible = 10, lastVisible = 18, size = 10_000)
        assertEquals(
            listOf(10, 11, 12, 13, 14, 15, 16, 17, 18),
            window.take(9),
            "the rows on screen must resolve first, top to bottom",
        )
    }

    @Test
    fun `neighbours follow outward - nearest first - in both directions`() {
        val window = GuideEpgPrefetchPolicy.windowFor(firstVisible = 10, lastVisible = 18, size = 10_000, radius = 2)
        assertEquals(listOf(10, 11, 12, 13, 14, 15, 16, 17, 18, 9, 19, 8, 20), window)
    }

    @Test
    fun `the ask is bounded by the visible range - never by how far the list was scrolled`() {
        // The bug: a fling across a 10,000-row lineup requested a row per composed row. Whatever
        // the scroll distance, one settle may only ever ask for a screenful plus the radius.
        val window = GuideEpgPrefetchPolicy.windowFor(firstVisible = 4_000, lastVisible = 4_008, size = 10_000)
        assertEquals(9 + GuideEpgPrefetchPolicy.RADIUS * 2, window.size)
        assertTrue(window.size < 20, "a settle must never fan out across the lineup: was ${window.size}")
    }

    @Test
    fun `clipped at the top of the list`() {
        val window = GuideEpgPrefetchPolicy.windowFor(firstVisible = 0, lastVisible = 2, size = 10, radius = 3)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), window, "there is nothing above row 0 to prefetch")
    }

    @Test
    fun `clipped at the bottom of the list`() {
        val window = GuideEpgPrefetchPolicy.windowFor(firstVisible = 7, lastVisible = 9, size = 10, radius = 3)
        assertEquals(listOf(7, 8, 9, 6, 5, 4), window, "there is nothing below the last row to prefetch")
    }

    @Test
    fun `a last index past the end is clamped rather than requested`() {
        val window = GuideEpgPrefetchPolicy.windowFor(firstVisible = 8, lastVisible = 40, size = 10, radius = 1)
        assertEquals(listOf(8, 9, 7), window)
    }

    @Test
    fun `no duplicates - so a row is never asked for twice in one settle`() {
        val window = GuideEpgPrefetchPolicy.windowFor(firstVisible = 0, lastVisible = 9, size = 10, radius = 5)
        assertEquals(window.size, window.toSet().size, "duplicate rows would double the request count")
    }

    @Test
    fun `an empty lineup asks for nothing`() {
        assertEquals(emptyList(), GuideEpgPrefetchPolicy.windowFor(firstVisible = 0, lastVisible = 8, size = 0))
    }

    @Test
    fun `an unmeasured LazyColumn with no visible items asks for nothing`() {
        // What layoutInfo reports before the first measure pass: first=0, last=-1.
        assertEquals(emptyList(), GuideEpgPrefetchPolicy.windowFor(firstVisible = 0, lastVisible = -1, size = 10_000))
    }
}
