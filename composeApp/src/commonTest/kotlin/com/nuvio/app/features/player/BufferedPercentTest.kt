package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression pin for the live `.ts` crash: media3's `getBufferedPercentage()` throws
 * `IllegalArgumentException("Out of range")` when `bufferedPosition * 100 / duration` overflows Int
 * (a garbage PTS-wrap live duration). [BufferedPercent.of] must clamp instead of throwing.
 * Runs on both the JVM and Kotlin/Native (iOS) runners.
 */
class BufferedPercentTest {

    @Test
    fun overflowShapedInputClampsInsteadOfThrowing() {
        // The exact crash class: the raw media3 computation would overflow Int and throw here.
        assertEquals(100, BufferedPercent.of(9_000_000_000L, 5L))
    }

    @Test
    fun negativeBufferedPositionClampsToZero() {
        assertEquals(0, BufferedPercent.of(-85_401_695_650L, 100L))
    }

    @Test
    fun timeUnsetOrNegativeDurationIsZero() {
        assertEquals(0, BufferedPercent.of(1_000L, -1L))
    }

    @Test
    fun zeroDurationIsFull() {
        assertEquals(100, BufferedPercent.of(0L, 0L))
    }

    @Test
    fun normalCaseIsProportional() {
        assertEquals(30, BufferedPercent.of(30_000L, 100_000L))
    }
}
