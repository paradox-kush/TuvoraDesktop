package com.nuvio.app.features.radar.alerts

import com.nuvio.app.core.sync.AppWindowPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Desktop-only choices: "during playback" is Never (the native video layer covers Compose), and alerts follow whether the
 * window is open, not whether it has focus. kotlin.test: assertEquals(expected, actual, message).
 */
class SportsAlertsDesktopTest {
    @Test
    fun `desktop reads during playback as never whatever is stored`() {
        for (stored in DuringPlayback.entries) {
            val read = platformSettings(SportsAlertSettings(duringPlayback = stored), desktop = true)
            assertEquals(DuringPlayback.Never, read.duringPlayback, "desktop never banners over a player (stored $stored)")
        }
    }

    @Test
    fun `desktop override leaves every other setting alone`() {
        val stored = SportsAlertSettings(enabled = false, maxPerHour = 3, batchSeconds = 0, duringPlayback = DuringPlayback.Always)
        val read = platformSettings(stored, desktop = true)
        assertEquals(stored.copy(duringPlayback = DuringPlayback.Never), read, "only duringPlayback changes")
    }

    @Test
    fun `a phone keeps the stored during playback choice`() {
        val stored = SportsAlertSettings(duringPlayback = DuringPlayback.Always)
        assertSame(stored, platformSettings(stored, desktop = false), "no copy, no change")
    }

    @Test
    fun `desktop follows window presence and ignores focus`() = runTest {
        // Focus lost (process foreground false) while the window stays open: alerts keep running.
        val seen = alertsForeground(desktop = true, processForeground = flowOf(false), windowOpen = flowOf(true, true, false, true)).toList()
        assertEquals(listOf(true, false, true), seen, "open, then minimised, then open again; repeats collapsed")
    }

    @Test
    fun `a phone follows the process lifecycle and ignores window presence`() = runTest {
        val seen = alertsForeground(desktop = false, processForeground = flowOf(true, false), windowOpen = flowOf(false)).toList()
        assertEquals(listOf(true, false), seen, "process lifecycle only")
    }

    @Test
    fun `window presence starts open and follows set`() {
        AppWindowPresence.set(true)
        assertTrue(AppWindowPresence.open.value, "open by default")
        AppWindowPresence.set(false)
        assertEquals(false, AppWindowPresence.open.value, "minimised")
        AppWindowPresence.set(true)
    }
}
