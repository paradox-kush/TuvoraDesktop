package com.nuvio.app.core.rec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [recIsoTimestamp] is hand-rolled calendar arithmetic, so it gets tested rather than trusted.
 * A wrong timestamp would not crash anything — it would quietly corrupt the ordering of the
 * training data, which is the one thing a sequence recommender cannot tolerate.
 */
class RecTimeTest {

    @Test
    fun formatsTheEpoch() {
        assertEquals("1970-01-01T00:00:00.000Z", recIsoTimestamp(0L))
    }

    @Test
    fun formatsAKnownInstant() {
        assertEquals("2026-08-05T09:15:03.412Z", recIsoTimestamp(1_785_921_303_412L))
    }

    @Test
    fun handlesLeapDay() {
        // 2024-02-29T12:00:00.000Z — the case a naive day-count gets wrong.
        assertEquals("2024-02-29T12:00:00.000Z", recIsoTimestamp(1_709_208_000_000L))
    }

    @Test
    fun handlesTheDayAfterALeapDay() {
        assertEquals("2024-03-01T00:00:00.000Z", recIsoTimestamp(1_709_251_200_000L))
    }

    @Test
    fun handlesCenturyNonLeapYear() {
        // 1900 was NOT a leap year; 2000 was. Both sit inside the era arithmetic.
        assertEquals("2000-02-29T00:00:00.000Z", recIsoTimestamp(951_782_400_000L))
    }

    @Test
    fun handlesPreEpochInstantsWithoutRollingBackwards() {
        // Floor division, not truncation: a clock set before 1970 must still produce a valid
        // date rather than a negative time-of-day.
        val formatted = recIsoTimestamp(-1L)
        assertEquals("1969-12-31T23:59:59.999Z", formatted)
    }

    @Test
    fun alwaysProducesAnInstantTheBackendCanParse() {
        val pattern = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
        val samples = listOf(0L, 1L, 1_000L, 1_785_921_303_412L, 4_102_444_800_000L, -1L)
        for (sample in samples) {
            assertTrue(
                pattern.matches(recIsoTimestamp(sample)),
                "not ISO-8601: ${recIsoTimestamp(sample)}",
            )
        }
    }
}
