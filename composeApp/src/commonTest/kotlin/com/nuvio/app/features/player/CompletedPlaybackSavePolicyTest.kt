package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletedPlaybackSavePolicyTest {

    @Test
    fun inProgressSaveBeforeCompletionIsKept() {
        assertFalse(
            CompletedPlaybackSavePolicy.shouldSkip(isCompleted = false, completionRecorded = false),
            "a normal mid-episode save must persist",
        )
    }

    @Test
    fun firstCompletedSaveIsKept() {
        assertFalse(
            CompletedPlaybackSavePolicy.shouldSkip(isCompleted = true, completionRecorded = false),
            "the save that crosses completion must persist",
        )
    }

    @Test
    fun staleNonCompletedSaveAfterCompletionIsSkipped() {
        // Regression: after natural end a stale snapshot (duration 0, not ended) read as in-progress
        // and overwrote the completed entry locally and on remote.
        assertTrue(
            CompletedPlaybackSavePolicy.shouldSkip(isCompleted = false, completionRecorded = true),
            "a stale in-progress save must not revert a completed video",
        )
    }

    @Test
    fun repeatCompletedSaveAfterCompletionIsKept() {
        assertFalse(
            CompletedPlaybackSavePolicy.shouldSkip(isCompleted = true, completionRecorded = true),
            "completed saves stay idempotent so a tick-recorded completion still reaches remote",
        )
    }
}
