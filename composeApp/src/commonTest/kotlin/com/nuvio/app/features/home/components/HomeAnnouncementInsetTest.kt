package com.nuvio.app.features.home.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The announcement card sits at the very top of Home. On tablets a floating tab-bar pill is drawn
 * OVER the content at the top; the card must start below it, not under the status bar (iPad bug:
 * the pill half-covered the card's title).
 */
class HomeAnnouncementInsetTest {

    private val listDefault = 64.dp // status bar 24 + screenTop 40

    @Test
    fun `phone with a bottom nav uses the screen's default top inset`() {
        assertEquals(
            listDefault,
            homeAnnouncementTopInset(defaultTopInset = listDefault, topChromePadding = null, topNavOverlay = 0.dp),
        )
    }

    @Test
    fun `tablet top pill pushes the card below the pill plus a gap`() {
        // pill box = status bar 24 + 10 top + 48 pill + 8 bottom = 90
        assertEquals(
            98.dp,
            homeAnnouncementTopInset(defaultTopInset = listDefault, topChromePadding = null, topNavOverlay = 90.dp),
        )
    }

    @Test
    fun `desktop top chrome padding is honoured as is`() {
        assertEquals(
            112.dp,
            homeAnnouncementTopInset(defaultTopInset = listDefault, topChromePadding = 112.dp, topNavOverlay = 0.dp),
        )
    }

    @Test
    fun `the inset never drops below the default`() {
        assertEquals(
            listDefault,
            homeAnnouncementTopInset(defaultTopInset = listDefault, topChromePadding = 20.dp, topNavOverlay = 10.dp),
        )
    }

    @Test
    fun `as a list item only the part the list padding does not cover is added`() {
        assertEquals(34.dp, homeAnnouncementItemTopInset(required = 98.dp, listTopPadding = listDefault))
        assertEquals(0.dp, homeAnnouncementItemTopInset(required = listDefault, listTopPadding = listDefault))
        assertEquals(0.dp, homeAnnouncementItemTopInset(required = 40.dp, listTopPadding = listDefault))
    }
}
