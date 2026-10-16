package com.nuvio.app.features.player.desktop

import java.awt.Rectangle

/**
 * Where the desktop player side panel's window sits: flush with the right edge of the owner's content area, full
 * height, never wider than the content. Pure (AWT logical pixels == dp on desktop), so it is unit-tested.
 */
internal object PlayerSidePanelBounds {
    fun of(contentX: Int, contentY: Int, contentWidth: Int, contentHeight: Int, panelWidth: Int): Rectangle {
        val width = panelWidth.coerceIn(0, contentWidth.coerceAtLeast(0))
        return Rectangle(contentX + contentWidth - width, contentY, width, contentHeight.coerceAtLeast(0))
    }
}
