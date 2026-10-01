package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** K9: an item restored at a lazy list's leading edge must come back into view, not one slot off-screen. */
class RestoredItemAnchorPolicyTest {

    private val row = listOf("sky-news", "sky-sports", "sky-movies", "bbc-one")

    @Test
    fun `undoing a hide of the first card pins the row back to its start`() {
        val afterHide = row - "sky-news"
        assertEquals(0, RestoredItemAnchorPolicy.restoredSlot(afterHide, row, anchorKey = "sky-sports"))
    }

    @Test
    fun `a card restored at the leading edge of a scrolled row comes back at that edge`() {
        val afterHide = row - "sky-movies"
        assertEquals(2, RestoredItemAnchorPolicy.restoredSlot(afterHide, row, anchorKey = "bbc-one"))
    }

    @Test
    fun `a card restored off-screen to the left does not yank a scrolled row`() {
        val afterHide = row - "sky-news"
        assertNull(RestoredItemAnchorPolicy.restoredSlot(afterHide, row, anchorKey = "bbc-one"))
    }

    @Test
    fun `a card restored after the anchor needs no correction`() {
        val afterHide = row - "bbc-one"
        assertNull(RestoredItemAnchorPolicy.restoredSlot(afterHide, row, anchorKey = "sky-news"))
    }

    @Test
    fun `appends and removals and first loads and replaced lists keep the list anchor`() {
        assertNull(RestoredItemAnchorPolicy.restoredSlot(row, row + "itv-one", anchorKey = "sky-sports"))
        assertNull(RestoredItemAnchorPolicy.restoredSlot(row, row - "sky-news", anchorKey = "sky-news"))
        assertNull(RestoredItemAnchorPolicy.restoredSlot(row, row - "sky-news", anchorKey = "sky-sports"))
        assertNull(RestoredItemAnchorPolicy.restoredSlot(emptyList(), row, anchorKey = null))
        assertNull(RestoredItemAnchorPolicy.restoredSlot(row, listOf("a", "b", "c"), anchorKey = "sky-news"))
    }
}
