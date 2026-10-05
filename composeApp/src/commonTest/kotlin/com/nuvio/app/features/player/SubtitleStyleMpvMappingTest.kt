package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleStyleMpvMappingTest {

    private fun props(bgHex: String, bgAlpha: Float, outline: Double = 3.0, margin: Int = 0) =
        SubtitleStyleMpvMapping.properties(
            backgroundColorHex = bgHex,
            backgroundAlpha = bgAlpha,
            outlineColorHex = "#FF000000",
            outlineSize = outline,
            sideMarginPercent = margin,
        ).toMap()

    @Test
    fun dimBackgroundUsesBackgroundBoxNotOpaqueBox() {
        // UX61: opaque-box paints the OUTLINE colour (opaque black), so "dim" looked solid.
        val p = props("#8C000000", 0.55f)
        assertEquals("background-box", p["sub-border-style"])
        assertEquals("#8C000000", p["sub-back-color"])
        assertEquals("4.0", p["sub-shadow-offset"])
    }

    @Test
    fun dimBackgroundStillShowsWhenOutlineIsEnabled() {
        // Android used to pick outline-and-shadow whenever the outline was on: no box at all.
        val p = props("#8C000000", 0.55f, outline = 3.0)
        assertEquals("background-box", p["sub-border-style"])
        assertEquals("3.0", p["sub-border-size"])
    }

    @Test
    fun transparentBackgroundIsAPlainOutlineWithNoShadow() {
        val p = props("#00000000", 0f)
        assertEquals("outline-and-shadow", p["sub-border-style"])
        assertEquals("0.0", p["sub-shadow-offset"])
    }

    @Test
    fun outlineUsesTheBorderAliasesThatMpv038AlsoAccepts() {
        val p = props("#00000000", 0f)
        assertEquals("#FF000000", p["sub-border-color"])
        assertEquals(setOf("sub-back-color", "sub-border-color", "sub-border-size", "sub-border-style", "sub-shadow-offset", "sub-margin-x"), p.keys)
    }

    @Test
    fun sideMarginIsAPercentOfThe1280ReferenceWidthAndNeverBelowMpvDefault() {
        assertEquals(64.0, SubtitleStyleMpvMapping.marginX(5))
        assertEquals(SubtitleStyleMpvMapping.MPV_DEFAULT_MARGIN_X, SubtitleStyleMpvMapping.marginX(0))
        assertEquals(256.0, SubtitleStyleMpvMapping.marginX(99)) // clamped to 20%
        assertEquals("64.0", props("#00000000", 0f, margin = 5)["sub-margin-x"])
    }

    @Test
    fun viewRendererPaddingMatchesThePercent() {
        assertEquals(96, SubtitleSideMargin.paddingPx(1920, 5))
        assertEquals(0, SubtitleSideMargin.paddingPx(1920, 0))
        assertEquals(384, SubtitleSideMargin.paddingPx(1920, 50))
    }

    @Test
    fun storageHexIsMpvsAlphaFirstFormat() {
        val dim = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)
        assertEquals("#8C000000", dim.toStorageHexString())
    }
}
