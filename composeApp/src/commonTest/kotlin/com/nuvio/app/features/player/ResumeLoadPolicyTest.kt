package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResumeLoadPolicyTest {

    @Test
    fun a_vod_load_with_a_saved_position_is_a_resume() {
        assertTrue(ResumeLoadPolicy.isResumeLoad(initialPositionMs = 822_000L, isLive = false))
    }

    @Test
    fun a_load_from_zero_or_a_live_channel_is_not_a_resume() {
        assertFalse(ResumeLoadPolicy.isResumeLoad(initialPositionMs = 0L, isLive = false))
        assertFalse(ResumeLoadPolicy.isResumeLoad(initialPositionMs = 822_000L, isLive = true))
    }

    @Test
    fun start_over_is_offered_once_a_resume_has_waited_long_enough_without_a_frame() {
        assertFalse(
            ResumeLoadPolicy.offerStartOver(isResumeLoad = true, firstFrameShown = false, loadingForMs = 14_999L),
            "not before the threshold",
        )
        assertTrue(
            ResumeLoadPolicy.offerStartOver(isResumeLoad = true, firstFrameShown = false, loadingForMs = 15_000L),
            "at the threshold",
        )
    }

    @Test
    fun start_over_is_never_offered_once_playback_started_or_for_a_fresh_start() {
        assertFalse(
            ResumeLoadPolicy.offerStartOver(isResumeLoad = true, firstFrameShown = true, loadingForMs = 60_000L),
            "already playing",
        )
        assertFalse(
            ResumeLoadPolicy.offerStartOver(isResumeLoad = false, firstFrameShown = false, loadingForMs = 60_000L),
            "a fresh start has nothing to fall back to",
        )
    }

    @Test
    fun the_threshold_is_fifteen_seconds() {
        assertEquals(15_000L, ResumeLoadPolicy.START_OVER_OFFER_AFTER_MS)
    }

    // Regression (emulator, 2026-09-27): the offer keyed off "the file opened" (snapshot not loading),
    // but a slow provider stalls AFTER the open — on the jump to the saved position — so the loading
    // screen and its button vanished while the viewer stared at a buffering spinner.
    @Test
    fun an_opened_file_that_has_drawn_no_frame_has_not_started() {
        assertFalse(
            ResumeLoadPolicy.playbackStarted(videoProgressTicks = 0L, hasVideoTrack = true, positionMs = 64_000L, initialPositionMs = 64_000L),
            "opened, seeking, no frame yet",
        )
    }

    @Test
    fun the_first_frame_starts_playback() {
        assertTrue(ResumeLoadPolicy.playbackStarted(videoProgressTicks = 1L, hasVideoTrack = true, positionMs = 64_000L, initialPositionMs = 64_000L))
    }

    @Test
    fun audio_only_starts_when_the_position_moves() {
        assertFalse(ResumeLoadPolicy.playbackStarted(0L, hasVideoTrack = false, positionMs = 64_500L, initialPositionMs = 64_000L), "not yet")
        assertTrue(ResumeLoadPolicy.playbackStarted(0L, hasVideoTrack = false, positionMs = 66_000L, initialPositionMs = 64_000L), "moving")
    }
}
