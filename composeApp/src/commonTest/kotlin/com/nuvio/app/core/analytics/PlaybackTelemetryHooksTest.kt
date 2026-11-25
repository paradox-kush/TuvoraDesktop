package com.nuvio.app.core.analytics

import com.nuvio.app.core.telemetry.api.PlaybackKind
import com.nuvio.app.core.telemetry.api.PlaybackSignal
import com.nuvio.app.core.telemetry.api.StallOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/** The freeze reporter hands every resolved stall to the playback telemetry port, in addition to its (rate-capped) event. */
class PlaybackTelemetryHooksTest {
    private val signals = ArrayList<PlaybackSignal>()
    private val reporter = LivePlaybackFreezeReporter(capture = { _, _ -> }, recordPlayback = { signals += it })

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
    fun `a recovered freeze records a recovered live stall`() {
        playUntilVideoStalls()
        sample(24_000L, 700L)
        sample(27_500L, 730L)
        val stall = signals.single() as PlaybackSignal.Stall
        assertEquals(PlaybackKind.LIVE, stall.kind)
        assertEquals(StallOutcome.RECOVERED, stall.outcome)
    }

    @Test
    fun `a freeze the ladder gave up on records a gave up live stall`() {
        playUntilVideoStalls()
        sample(26_500L, 700L)
        reporter.onRecoveryGaveUp(nowMs = 28_000L)
        val stall = signals.single() as PlaybackSignal.Stall
        assertEquals(StallOutcome.GAVE_UP, stall.outcome)
    }

    @Test
    fun `a freeze still open when the viewer leaves records an abandoned live stall`() {
        playUntilVideoStalls()
        sample(26_500L, 700L)
        reporter.onLivePlaybackStopped(nowMs = 30_000L, positionMs = 30_000L, bufferedPositionMs = 32_000L)
        val stall = signals.single() as PlaybackSignal.Stall
        assertEquals(PlaybackKind.LIVE, stall.kind)
        assertEquals(StallOutcome.ABANDONED, stall.outcome)
        assertEquals(10_000L, stall.ms, "counted from the video stall start")
    }

    @Test
    fun `a freeze that gave up is not counted again as abandoned on leave`() {
        playUntilVideoStalls()
        sample(26_500L, 700L)
        reporter.onRecoveryGaveUp(nowMs = 28_000L)
        reporter.onLivePlaybackStopped(nowMs = 30_000L, positionMs = 30_000L, bufferedPositionMs = 32_000L)
        assertEquals(StallOutcome.GAVE_UP, (signals.single() as PlaybackSignal.Stall).outcome)
    }

    @Test
    fun `a recovered freeze is not counted again as abandoned on leave`() {
        playUntilVideoStalls()
        sample(24_000L, 700L)
        sample(27_500L, 730L)
        reporter.onLivePlaybackStopped(nowMs = 40_000L, positionMs = 40_000L, bufferedPositionMs = 45_000L)
        assertEquals(StallOutcome.RECOVERED, (signals.single() as PlaybackSignal.Stall).outcome)
    }

    @Test
    fun `healthy playback records no stall`() {
        reporter.onLivePlaybackStarted(profile(), nowMs = 0L, positionMs = 0L, videoProgressTicks = 100L)
        var ticks = 100L
        for (t in listOf(10_000L, 20_000L, 30_000L)) { ticks += 300L; sample(t, ticks) }
        reporter.onLivePlaybackStopped(nowMs = 31_000L, positionMs = 31_000L, bufferedPositionMs = 36_000L)
        assertEquals(emptyList(), signals)
    }
}
