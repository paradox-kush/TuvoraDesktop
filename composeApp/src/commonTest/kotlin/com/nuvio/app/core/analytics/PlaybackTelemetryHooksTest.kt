package com.nuvio.app.core.analytics

import com.nuvio.app.core.telemetry.RecordingTelemetryPort
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The freeze reporter counts every resolved stall into session_health, in addition to its (rate-capped) event. */
class PlaybackTelemetryHooksTest {
    private val port = RecordingTelemetryPort { _, _ -> }
    private val reporter = LivePlaybackFreezeReporter(capture = { _, _ -> })

    @AfterTest
    fun reset() = TelemetryAccess.resetForTest()

    private fun profile() = LivePlaybackFreezeReporter.Profile(
        engine = "libmpv", streamContainer = "ts", iptvKind = "xtream", surface = "player", buffers = null,
    )

    private fun sample(nowMs: Long, ticks: Long) = reporter.onSample(
        nowMs = nowMs, positionMs = nowMs, bufferedPositionMs = nowMs + 5_000L,
        state = LivePlaybackFreezePolicy.PlaybackState.READY, wantsToPlay = true,
        videoProgressTicks = ticks, hasVideoTrack = true,
    )

    private fun playUntilVideoStalls() {
        reporter.onLivePlaybackStarted(profile(), nowMs = 0L, positionMs = 0L, videoProgressTicks = 100L)
        var ticks = 100L
        var t = 0L
        while (t < 20_000L) { t += 10_000L; ticks += 300L; sample(t, ticks) }
    }

    @Test
    fun `a recovered freeze counts recovered and its duration bucket`() {
        TelemetryAccess.register(port)
        playUntilVideoStalls()
        sample(24_000L, 700L)
        sample(27_500L, 730L)
        assertEquals(listOf("stall:live:recovered", "stall_ms:live:lt15000"), port.counts)
    }

    @Test
    fun `a freeze the viewer left counts gave_up`() {
        TelemetryAccess.register(port)
        playUntilVideoStalls()
        sample(26_500L, 700L)
        reporter.onLivePlaybackStopped(nowMs = 30_000L, positionMs = 30_000L, bufferedPositionMs = 32_000L)
        assertEquals(listOf("stall:live:gave_up", "stall_ms:live:lt15000"), port.counts)
    }

    @Test
    fun `healthy playback counts no stall`() {
        TelemetryAccess.register(port)
        reporter.onLivePlaybackStarted(profile(), nowMs = 0L, positionMs = 0L, videoProgressTicks = 100L)
        var ticks = 100L
        for (t in listOf(10_000L, 20_000L, 30_000L)) { ticks += 300L; sample(t, ticks) }
        reporter.onLivePlaybackStopped(nowMs = 31_000L, positionMs = 31_000L, bufferedPositionMs = 36_000L)
        assertEquals(emptyList(), port.counts)
    }
}
