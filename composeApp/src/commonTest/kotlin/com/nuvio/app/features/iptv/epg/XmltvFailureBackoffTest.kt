package com.nuvio.app.features.iptv.epg

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XmltvFailureBackoffTest {
    private val hour = XmltvFailureBackoff.WINDOW_MS

    @Test
    fun a_playlist_that_never_failed_may_fetch() {
        assertTrue(XmltvFailureBackoff.allows(lastFailedMs = null, nowMs = 1_000L))
    }

    @Test
    fun a_recent_failure_blocks_the_refetch_for_the_window() {
        // Regression (B10, 2026-09-27): a failed ingest writes no meta row, so every hub tile's
        // ensureEpg re-downloaded the whole guide after one failure.
        assertFalse(XmltvFailureBackoff.allows(lastFailedMs = 10_000L, nowMs = 10_000L + hour - 1), "inside the window")
        assertTrue(XmltvFailureBackoff.allows(lastFailedMs = 10_000L, nowMs = 10_000L + hour), "window elapsed")
    }
}
