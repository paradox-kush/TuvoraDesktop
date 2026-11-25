package com.nuvio.app.core.telemetry.api

/**
 * Closed bucket vocabularies for the playback telemetry contract. Pure: no clock, no platform, so the boundaries are
 * unit-tested and identical on every platform (TV, Mobile, Desktop, Apple TV mirror these strings).
 *
 * Boundaries are exclusive upper bounds: `lt250` is `ms < 250`, so 250 itself is `lt500`.
 */
object PlaybackTelemetryBuckets {
    /** Milliseconds from play request to the first rendered frame. */
    val TTFF: List<String> = listOf("lt250", "lt500", "lt1000", "lt2000", "lt4000", "lt8000", "ge8000")

    /** Milliseconds a stall lasted. */
    val STALL: List<String> = listOf("lt2000", "lt5000", "lt15000", "lt45000", "ge45000")

    /** Seconds a multiview session lasted (multiview is TV only; kept here so the strings stay shared). */
    val DURATION_S: List<String> = listOf("lt30", "lt120", "lt600", "ge600")

    val RAM_CLASSES: List<String> = listOf("lt1_2", "lt2", "lt3", "ge3")
    val RES_CLASSES: List<String> = listOf("sd", "hd720", "fhd", "uhd", "unknown")

    /** A teardown slower than this is `slow`. */
    const val SLOW_TEARDOWN_MS = 1_500L

    fun ttff(ms: Long): String = when {
        ms < 250L -> "lt250"
        ms < 500L -> "lt500"
        ms < 1_000L -> "lt1000"
        ms < 2_000L -> "lt2000"
        ms < 4_000L -> "lt4000"
        ms < 8_000L -> "lt8000"
        else -> "ge8000"
    }

    fun stall(ms: Long): String = when {
        ms < 2_000L -> "lt2000"
        ms < 5_000L -> "lt5000"
        ms < 15_000L -> "lt15000"
        ms < 45_000L -> "lt45000"
        else -> "ge45000"
    }

    fun durationS(seconds: Long): String = when {
        seconds < 30L -> "lt30"
        seconds < 120L -> "lt120"
        seconds < 600L -> "lt600"
        else -> "ge600"
    }

    /**
     * Total device RAM in MB (binary: 1.2 GB = 1229 MB). Null when unknown: the property is then left out rather than
     * guessed.
     */
    fun ramClass(totalMb: Long?): String? = when {
        totalMb == null || totalMb <= 0L -> null
        totalMb < 1_229L -> "lt1_2"
        totalMb < 2_048L -> "lt2"
        totalMb < 3_072L -> "lt3"
        else -> "ge3"
    }

    /** Video height in pixels. */
    fun resClass(height: Int?): String = when {
        height == null || height <= 0 -> "unknown"
        height < 720 -> "sd"
        height < 1_080 -> "hd720"
        height < 2_160 -> "fhd"
        else -> "uhd"
    }
}
