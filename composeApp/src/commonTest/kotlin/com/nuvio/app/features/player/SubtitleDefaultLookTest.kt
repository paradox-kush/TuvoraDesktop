package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F47 (owner-approved 2026-10-04): the reporter's reference look is the default for new users. */
class SubtitleDefaultLookTest {

    @Test
    fun newUsersGetWhiteTextInASoftBoxWithNoOutline() {
        val style = SubtitleStyleState.DEFAULT
        assertEquals(Color.White, style.textColor)
        assertFalse(style.outlineEnabled)
        assertTrue(style.backgroundColor.alpha in 0.4f..0.75f, "soft translucent, not solid")
        assertEquals(0f, style.backgroundColor.red)
        // The reporter circled the space INSIDE the box, not the screen margin.
        assertEquals(0, style.sideMarginPercent)
    }

    @Test
    fun theDefaultBoxIsAPaddedBackgroundBoxOnMpv() {
        val style = SubtitleStyleState.DEFAULT
        val p = SubtitleStyleMpvMapping.properties(
            backgroundColorHex = style.backgroundColor.toStorageHexString(),
            backgroundAlpha = style.backgroundColor.alpha,
            outlineColorHex = style.outlineColor.toStorageHexString(),
            outlineSize = 0.0,
            sideMarginPercent = style.sideMarginPercent,
        ).toMap()
        assertEquals("background-box", p["sub-border-style"])
        assertEquals("0.0", p["sub-border-size"])
        assertEquals(SubtitleStyleMpvMapping.BOX_PADDING, p["sub-shadow-offset"]!!.toDouble())
        assertTrue(SubtitleStyleMpvMapping.BOX_PADDING >= 8.0, "visible inner padding, not a tight box")
    }

    @Test
    fun anyStoredStyleFieldMeansACustomizerWhoKeepsTheOldDefaults() {
        assertEquals(SubtitleStyleState.DEFAULT, SubtitleStyleDefaults.baseFor(anyFieldStored = false))
        val legacy = SubtitleStyleDefaults.baseFor(anyFieldStored = true)
        assertEquals(SubtitleStyleDefaults.LEGACY, legacy)
        assertTrue(legacy.outlineEnabled)
        assertEquals(Color.Transparent, legacy.backgroundColor)
    }

    @Test
    fun exoPlayerBoxGetsInnerPaddingOnEveryLine() {
        val padded = SubtitleBoxPadding.padLines("Hello\nwide world", SubtitleBoxPadding.EXO_PAD_CHARS)
        val pad = SubtitleBoxPadding.PAD_CHAR.toString().repeat(SubtitleBoxPadding.EXO_PAD_CHARS)
        assertEquals("${pad}Hello$pad\n${pad}wide world$pad", padded)
        assertEquals("", SubtitleBoxPadding.padLines("", 1))
        assertEquals("x", SubtitleBoxPadding.padLines("x", 0))
    }
}
