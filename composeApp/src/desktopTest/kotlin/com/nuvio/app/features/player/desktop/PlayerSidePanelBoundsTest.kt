package com.nuvio.app.features.player.desktop

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerSidePanelBoundsTest {
    @Test
    fun `the panel window hugs the right edge of the player window at full height`() {
        assertEquals(Rectangle(100 + 1280 - 420, 50, 420, 720), PlayerSidePanelBounds.of(100, 50, 1280, 720, 420))
    }

    @Test
    fun `a window narrower than the panel gets a panel as wide as the window`() {
        assertEquals(Rectangle(0, 0, 300, 500), PlayerSidePanelBounds.of(0, 0, 300, 500, 420))
    }

    @Test
    fun `a collapsed window never yields negative sizes`() {
        assertEquals(Rectangle(10, 10, 0, 0), PlayerSidePanelBounds.of(10, 10, 0, -5, 420))
    }
}
