package com.nuvio.app.features.iptv.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * B108: un-pinning or un-renaming a channel did not update the hub rows already loaded until a
 * re-fetch. The hub baked pins and renames into the window it cached at FETCH time, while hides
 * were applied when rows were shown. The hub now caches the raw provider window and applies the
 * whole channel overlay when rows are shown ([IptvChannelQuickActionsPolicy.hubRow]), so every
 * edit and its undo show at once.
 */
class IptvHubRowOverlayRefreshTest {

    private data class Card(val id: String, val name: String = id, val pinned: Boolean = false)

    /** What the hub caches for a loaded row: the raw provider window. */
    private val lineup = listOf(Card("news"), Card("sports"), Card("movies"))
    private val entities = mapOf("news" to "e-news", "sports" to "e-sports", "movies" to "e-movies")

    private fun shown(overlay: Map<String, ChannelOverlay>, row: List<Card> = lineup) =
        IptvChannelQuickActionsPolicy.hubRow(
            items = row,
            overlay = overlay,
            entityOf = { entities[it.id] },
            withName = { c, n -> c.copy(name = n) },
            withPinned = { c -> c.copy(pinned = true) },
        )

    private val pinnedAndRenamed = mapOf("e-movies" to ChannelOverlay(pinned = true), "e-sports" to ChannelOverlay(rename = "Sport HD"))

    @Test
    fun `a pin and a rename made after the row loaded show without a re-fetch`() {
        assertEquals(
            listOf(Card("movies", pinned = true), Card("news"), Card("sports", "Sport HD")),
            shown(pinnedAndRenamed),
            "the pinned channel floats to the top with its marker and the rename shows",
        )
    }

    @Test
    fun `unpinning and unrenaming a channel after its row loaded restores it without a re-fetch`() {
        assertEquals(listOf(Card("movies", pinned = true), Card("news"), Card("sports", "Sport HD")), shown(pinnedAndRenamed))
        val cleared = mapOf("e-movies" to ChannelOverlay(pinned = false), "e-sports" to ChannelOverlay(rename = null))
        assertEquals(lineup, shown(cleared), "the un-pin and un-rename must show in the loaded row")
        assertEquals(lineup, shown(emptyMap()), "edits deleted outright (website reset) restore the row too")
    }

    @Test
    fun `a blank rename shows the provider name`() {
        assertEquals(lineup, shown(mapOf("e-sports" to ChannelOverlay(rename = "  "))))
    }

    @Test
    fun `pins keep provider order among themselves`() {
        val overlay = mapOf("e-movies" to ChannelOverlay(pinned = true), "e-sports" to ChannelOverlay(pinned = true))
        assertEquals(listOf("sports", "movies", "news"), shown(overlay).map { it.id })
    }

    @Test
    fun `a card with no known identity is kept untouched`() {
        val row = lineup + Card("mystery")
        assertEquals(
            listOf(Card("movies", pinned = true), Card("news"), Card("sports", "Sport HD"), Card("mystery")),
            shown(pinnedAndRenamed, row),
        )
    }

    @Test
    fun `a row with no edited channel in it is returned as is`() {
        assertSame(lineup, shown(emptyMap()))
        assertSame(lineup, shown(mapOf("e-elsewhere" to ChannelOverlay(pinned = true))))
        assertSame(lineup, shown(mapOf("e-news" to ChannelOverlay())), "a no-op edit leaves the row alone")
    }
}
