package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The downloader reports on every read: about 7,000 reports for a 58 MB installer, each one rebuilt the updater
 * state and woke everything reading it (B274). kotlin.test order: assertEquals(expected, actual, message).
 */
class UpdateProgressThrottleTest {

    @Test
    fun `a full download publishes a few times a second at most, not per read`() {
        val throttle = UpdateProgressThrottle()
        val total = 58L * 1024 * 1024
        val chunk = 8192L
        var published = 0
        var downloaded = 0L
        var now = 0L
        // 1 ms per 8 KB read = a fast link: 58 MB in about 7 s.
        while (downloaded < total) {
            downloaded = (downloaded + chunk).coerceAtMost(total)
            now += 1
            if (throttle.accept(downloaded, total, now) != null) published++
        }
        assertTrue(published <= 40, "published $published times for ~7,000 reads")
    }

    @Test
    fun `first report publishes and the final one always does`() {
        val throttle = UpdateProgressThrottle()
        assertEquals(0.01f, throttle.accept(1, 100, 0), "first")
        assertEquals(1f, throttle.accept(100, 100, 1), "final")
    }

    @Test
    fun `same whole percent is skipped even after the interval`() {
        val throttle = UpdateProgressThrottle()
        throttle.accept(10, 1000, 0)
        assertNull(throttle.accept(15, 1000, 10_000), "same percent")
    }

    @Test
    fun `a new percent inside the interval waits`() {
        val throttle = UpdateProgressThrottle(minIntervalMs = 250)
        throttle.accept(10, 1000, 0)
        assertNull(throttle.accept(30, 1000, 100), "too soon")
        assertEquals(0.03f, throttle.accept(30, 1000, 250), "after the interval")
    }

    @Test
    fun `unknown total publishes nothing`() {
        val throttle = UpdateProgressThrottle()
        assertNull(throttle.accept(500, null, 0), "no total")
        assertNull(throttle.accept(500, 0, 1000), "zero total")
    }
}
