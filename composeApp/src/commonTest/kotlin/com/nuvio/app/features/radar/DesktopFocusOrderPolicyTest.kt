package com.nuvio.app.features.radar

import com.nuvio.app.features.radar.DesktopFocusOrderPolicy.Key
import com.nuvio.app.features.radar.DesktopFocusOrderPolicy.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopFocusOrderPolicyTest {
    private val p = Target.Primary
    private fun c(i: Int) = Target.Chip(i)

    @Test fun `tab walks primary then every chip then leaves`() {
        assertEquals(c(0), DesktopFocusOrderPolicy.next(p, Key.TAB, 3))
        assertEquals(c(1), DesktopFocusOrderPolicy.next(c(0), Key.TAB, 3))
        assertEquals(c(2), DesktopFocusOrderPolicy.next(c(1), Key.TAB, 3))
        assertNull(DesktopFocusOrderPolicy.next(c(2), Key.TAB, 3), "tab past the last chip leaves the group")
    }

    @Test fun `shift tab walks back and first chip returns to primary`() {
        assertEquals(c(0), DesktopFocusOrderPolicy.next(c(1), Key.SHIFT_TAB, 3))
        assertEquals(p, DesktopFocusOrderPolicy.next(c(0), Key.SHIFT_TAB, 3))
        assertNull(DesktopFocusOrderPolicy.next(p, Key.SHIFT_TAB, 3))
    }

    @Test fun `arrows move across chips only and stop at the ends`() {
        assertEquals(c(2), DesktopFocusOrderPolicy.next(c(1), Key.RIGHT, 3))
        assertNull(DesktopFocusOrderPolicy.next(c(2), Key.RIGHT, 3))
        assertEquals(c(0), DesktopFocusOrderPolicy.next(c(1), Key.LEFT, 3))
        assertNull(DesktopFocusOrderPolicy.next(c(0), Key.LEFT, 3))
    }

    @Test fun `down from a chip goes to primary and up from primary goes to the selected chip`() {
        assertEquals(p, DesktopFocusOrderPolicy.next(c(2), Key.DOWN, 3))
        assertEquals(c(1), DesktopFocusOrderPolicy.next(p, Key.UP, 3, selectedChip = 1))
        assertEquals(c(0), DesktopFocusOrderPolicy.next(p, Key.RIGHT, 3, selectedChip = 1))
    }

    @Test fun `no chips means primary has nowhere to go`() {
        assertNull(DesktopFocusOrderPolicy.next(p, Key.TAB, 0))
        assertNull(DesktopFocusOrderPolicy.next(p, Key.UP, 0))
    }

    @Test fun `clamp keeps focus on an existing control after the chip list shrinks`() {
        assertEquals(c(1), DesktopFocusOrderPolicy.clamp(c(3), 2))
        assertEquals(p, DesktopFocusOrderPolicy.clamp(c(0), 0))
        assertEquals(p, DesktopFocusOrderPolicy.clamp(p, 4))
    }

    @Test
    fun chipArrowTargetMovesWithinRowAndStopsAtEnds() {
        val k = DesktopFocusOrderPolicy.Key.RIGHT
        assertEquals(1, DesktopFocusOrderPolicy.chipArrowTarget(0, k, 3))
        assertEquals(2, DesktopFocusOrderPolicy.chipArrowTarget(1, k, 3))
        assertEquals(null, DesktopFocusOrderPolicy.chipArrowTarget(2, k, 3))
        assertEquals(0, DesktopFocusOrderPolicy.chipArrowTarget(1, DesktopFocusOrderPolicy.Key.LEFT, 3))
        assertEquals(null, DesktopFocusOrderPolicy.chipArrowTarget(0, DesktopFocusOrderPolicy.Key.LEFT, 3))
        assertEquals(null, DesktopFocusOrderPolicy.chipArrowTarget(1, DesktopFocusOrderPolicy.Key.TAB, 3))
        assertEquals(null, DesktopFocusOrderPolicy.chipArrowTarget(0, k, 1))
    }
}
