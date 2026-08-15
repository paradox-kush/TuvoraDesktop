package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KMP port of NuvioTV's XtreamCatchUpTest. The timezone-NAME tests stay TV-only (commonMain has
 * no timezone database); the clock-pair offset covers panel-local time here instead.
 */
class XtreamCatchUpTest {

    private val start = 1_710_000_000_000L   // 2024-03-09 16:00 UTC
    private val end = start + 60 * 60_000L   // one hour later

    @Test
    fun `start is formatted in the panel's expected shape`() {
        assertEquals("2024-03-09:16-00", XtreamCatchUp.formatStart(start))
    }

    @Test
    fun `duration is whole minutes and never zero`() {
        assertEquals(60, XtreamCatchUp.durationMinutes(start, end))
        assertEquals(90, XtreamCatchUp.durationMinutes(start, start + 90 * 60_000L))
        // A programme with no usable end still has to ask for something playable.
        assertEquals(1, XtreamCatchUp.durationMinutes(start, start))
        assertEquals(1, XtreamCatchUp.durationMinutes(start, start - 5_000L))
    }

    /**
     * Partial minutes floor — rounding up would request a final fragment past the recording's
     * end, which some panels answer with an error for the whole request.
     */
    @Test
    fun `duration floors partial minutes`() {
        assertEquals(61, XtreamCatchUp.durationMinutes(start, start + 61 * 60_000L + 30_000L))
        assertEquals(59, XtreamCatchUp.durationMinutes(start, start + 60 * 60_000L - 1L))
    }

    /**
     * Providers advertise a window in days via `tv_archive_duration`. Offering replay outside it
     * just fails, so the guide has to know before it shows the affordance.
     */
    @Test
    fun `a programme inside the provider's window is replayable`() {
        val now = start + 2 * DAY
        assertTrue(XtreamCatchUp.isWithinWindow(programmeStartMs = start, nowMs = now, catchUpDays = 3))
    }

    @Test
    fun `a programme older than the window is not`() {
        val now = start + 5 * DAY
        assertFalse(XtreamCatchUp.isWithinWindow(programmeStartMs = start, nowMs = now, catchUpDays = 3))
    }

    /** A future programme has not been recorded yet, whatever the window says. */
    @Test
    fun `a programme still to air is not replayable`() {
        assertFalse(
            XtreamCatchUp.isWithinWindow(programmeStartMs = start + DAY, nowMs = start, catchUpDays = 7)
        )
    }

    /**
     * Panels that report no window at all still serve catch-up — tv_archive is the flag, and
     * tv_archive_duration is frequently absent or zero. Treat that as "unknown, allow it" rather
     * than hiding a feature the provider does support.
     */
    @Test
    fun `an unknown window does not block replay`() {
        assertTrue(XtreamCatchUp.isWithinWindow(programmeStartMs = start, nowMs = start + DAY, catchUpDays = 0))
    }

    /**
     * Panels disagree about the catch-up URL shape, and a wrong guess is a dead channel rather than
     * a degraded one. These are the forms seen in the wild, best-known first: the XUI path form we
     * already shipped, the variant with the id and start SWAPPED, and the php form with and without
     * an explicit extension, at both the api path and the domain root.
     */
    @Test
    fun `every known panel dialect is offered best-known first`() {
        val urls = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com",
            username = "user",
            password = "pass",
            streamId = 777,
            startMs = start,
            endMs = end,
            containerExtension = "ts",
        )

        assertEquals(
            listOf(
                "https://example.com/timeshift/user/pass/60/2024-03-09:16-00/777.ts",
                "https://example.com/timeshifts/user/pass/60/777/2024-03-09:16-00.ts",
                "https://example.com/streaming/timeshift.php?username=user&password=pass&stream=777&start=2024-03-09:16-00&duration=60&extension=ts",
                "https://example.com/streaming/timeshift.php?username=user&password=pass&stream=777&start=2024-03-09:16-00&duration=60",
                "https://example.com/timeshift.php?username=user&password=pass&stream=777&start=2024-03-09:16-00&duration=60",
            ),
            urls,
        )
    }

    /** The first candidate must stay byte-identical to what shipped, or working panels regress. */
    @Test
    fun `the first candidate is the form we already shipped`() {
        val first = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com/", username = "user", password = "pass",
            streamId = 777, startMs = start, endMs = end, containerExtension = null,
        ).first()
        assertEquals("https://example.com/timeshift/user/pass/60/2024-03-09:16-00/777.ts", first)
    }

    /**
     * Blank or whitespace credentials cannot form a playable URL — building one anyway yields
     * `.../timeshift///60/...`, which fails looking like a provider fault. No URL at all is the
     * honest answer (iptvnator returns none from the same state).
     */
    @Test
    fun `blank credentials build no urls`() {
        fun urls(user: String, pass: String) = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = user, password = pass,
            streamId = 7, startMs = start, endMs = end, containerExtension = "ts",
        )
        assertEquals(emptyList(), urls("", "pass"))
        assertEquals(emptyList(), urls("user", ""))
        assertEquals(emptyList(), urls("   ", "pass"))
        assertEquals(emptyList(), urls("user", "\t"))
        assertEquals(emptyList(), urls("", ""))
    }

    @Test
    fun `credentials with url-unsafe characters are encoded`() {
        val first = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = "a b", password = "p/s",
            streamId = 5, startMs = start, endMs = end, containerExtension = "ts",
        ).first()
        assertTrue(first.contains("a%20b"), "username must be encoded: $first")
        assertTrue(first.contains("p%2Fs"), "password must be encoded: $first")
    }

    /**
     * Credentials carrying the characters that would break a query string (&, =, #) must arrive
     * percent-encoded in the php-query dialects, or the panel reads a different set of params.
     */
    @Test
    fun `credentials with query-breaking characters survive the php forms`() {
        val urls = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = "a&b", password = "p=q#r",
            streamId = 5, startMs = start, endMs = end, containerExtension = "ts",
        )
        val php = urls.filter { it.contains("timeshift.php") }
        assertEquals(3, php.size)
        php.forEach { url ->
            assertTrue(url.contains("username=a%26b"), url)
            assertTrue(url.contains("password=p%3Dq%23r"), url)
        }
        // ...and the raw separators appear nowhere in any dialect.
        urls.forEach { url ->
            assertFalse(url.contains("a&b"), url)
            assertFalse(url.contains("p=q#r"), url)
        }
    }

    /** A panel served under a path (reverse proxy / shared host) keeps that path in every dialect. */
    @Test
    fun `a base url with a path keeps the path`() {
        val urls = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com/xtream/", username = "u", password = "p",
            streamId = 7, startMs = start, endMs = end, containerExtension = "ts",
        )
        assertEquals("https://example.com/xtream/timeshift/u/p/60/2024-03-09:16-00/7.ts", urls.first())
        urls.forEach { url -> assertTrue(url.startsWith("https://example.com/xtream/"), url) }
    }

    /**
     * The airing programme asks for its FULL scheduled duration, future end included — both
     * reference players send it unclamped and panels serve what exists so far. Pinned so nobody
     * "fixes" it into a clamp that truncates start-over.
     */
    @Test
    fun `an airing programme requests the full duration unclamped`() {
        val urls = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = "u", password = "p",
            streamId = 7, startMs = start, endMs = start + 90 * 60_000L, containerExtension = "ts",
        )
        assertEquals("https://example.com/timeshift/u/p/90/2024-03-09:16-00/7.ts", urls.first())
        assertTrue(urls.last().contains("duration=90"), urls.last())
    }

    /**
     * The clock-pair offset (ServerClockOffset) is how commonMain speaks panel-local time: no
     * timezone database, just the measured shift, formatted as-if-UTC.
     */
    @Test
    fun `an explicit clock-pair offset shifts the start`() {
        assertEquals("2024-03-09:17-00", XtreamCatchUp.formatStart(start, serverOffsetMs = 3_600_000L))
        assertEquals("2024-03-09:11-00", XtreamCatchUp.formatStart(start, serverOffsetMs = -18_000_000L))
        // Half-hour zones exist (India +5:30) and must survive.
        assertEquals("2024-03-09:21-30", XtreamCatchUp.formatStart(start, serverOffsetMs = 19_800_000L))
        // A shift across midnight moves the date too.
        assertEquals("2024-03-10:01-00", XtreamCatchUp.formatStart(start, serverOffsetMs = 9 * 3_600_000L))
    }

    @Test
    fun `the clock-pair offset reaches the built urls`() {
        val url = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = "u", password = "p",
            streamId = 1, startMs = start, endMs = end, containerExtension = "ts",
            serverOffsetMs = 3_600_000L,
        ).first()
        assertTrue(url.contains("2024-03-09:17-00"), "expected the +1h panel-local time in $url")
    }

    private companion object { const val DAY = 24L * 60 * 60 * 1000 }
}
