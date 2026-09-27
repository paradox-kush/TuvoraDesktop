package com.nuvio.app.features.player

/**
 * Whether a player progress save should be dropped because this playback already recorded the
 * current video as completed.
 *
 * After natural completion the player can report a stale snapshot: not ended, duration 0, position
 * near the end. That reads as NOT completed, and saving it would replace the completed entry with an
 * in-progress one locally and on remote. So once completion is recorded, non-completed saves for the
 * same video are skipped. Completed saves still pass: they are idempotent, and letting them through
 * means a completion first recorded by a local-only tick still reaches remote on the next flush.
 */
internal object CompletedPlaybackSavePolicy {
    fun shouldSkip(isCompleted: Boolean, completionRecorded: Boolean): Boolean =
        completionRecorded && !isCompleted
}
