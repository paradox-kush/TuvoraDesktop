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
}
