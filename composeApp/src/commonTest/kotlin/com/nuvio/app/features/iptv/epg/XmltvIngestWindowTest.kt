package com.nuvio.app.features.iptv.epg

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The XMLTV ingest kept every programme a feed contained — no time bound at all. Survivable while
 * it ran for M3U playlists only; not survivable as the general rule, and the whole-guide lane for
 * Xtream panels would have multiplied it across every account. These pin the bound.
 */
class XmltvIngestWindowTest {

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun keeps(startOffsetMs: Long, durationMs: Long = hour) =
        XmltvIngestWindow.keeps(now + startOffsetMs, now + startOffsetMs + durationMs, now)

    @Test
    fun `a programme airing now is kept`() {
        assertTrue(keeps(-30 * 60 * 1000))
    }

    @Test
    fun `recent history is kept so the guide opens with a visible past`() {
        // The docked guide anchors an hour back, so that hour has to be on disk.
        assertTrue(keeps(-2 * hour), "two hours ago is inside the six-hour lookback")
        assertTrue(keeps(-5 * hour), "five hours ago is still inside it")
    }

    @Test
    fun `history older than the lookback is refused`() {
        assertFalse(keeps(-8 * hour), "eight hours ago is past the bound")
        assertFalse(keeps(-3 * day), "deep history belongs to the catch-up lane, not this one")
    }

    @Test
    fun `two days ahead is kept and a week ahead is refused`() {
        assertTrue(keeps(36 * hour), "a day and a half ahead is inside the forward horizon")
        assertFalse(keeps(7 * day), "a week of schedule is rows a budget device cannot afford")
    }

    @Test
    fun `a programme straddling the window edge is kept`() {
        // Started before the lookback but still running: the guide needs it to fill the row.
        assertTrue(XmltvIngestWindow.keeps(now - 9 * hour, now - 1 * hour, now))
    }

    @Test
    fun `a zero length programme is refused`() {
        // A feed with a broken date format produces thousands of these, and every window read
        // that spans the instant returns them.
        assertFalse(XmltvIngestWindow.keeps(now, now, now))
    }

    @Test
    fun `a backwards programme is refused`() {
        assertFalse(XmltvIngestWindow.keeps(now + hour, now, now))
    }

    @Test
    fun `an epoch-zero programme is refused`() {
        assertFalse(XmltvIngestWindow.keeps(0L, hour, now))
    }
}
