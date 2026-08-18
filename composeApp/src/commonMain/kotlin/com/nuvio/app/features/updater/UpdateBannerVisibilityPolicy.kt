package com.nuvio.app.features.updater

/**
 * Decides whether the in-app update banner may take layout space right now.
 *
 * This is NOT the "is an update worth announcing" question (version compare / dismissal) — it is
 * purely "may the banner occupy pixels given what is on screen".
 *
 * Why it has to exist: [AppUpdaterHost] puts the banner in a `Column` **above** the whole app
 * content, so the banner does not float over the UI — it *shrinks* it. On a browse screen that is
 * invisible. Over video it is destructive:
 *
 *  - In Picture-in-Picture the banner ate roughly 60% of the (already tiny) window and squeezed the
 *    picture into a sliver. Reproduced on an API 36 emulator, 2026-08-17: dismissing the banner and
 *    re-entering PiP with no other change gave a correctly filled video window. That is the bug
 *    reported as "PiP only plays audio and shows no video".
 *  - Full screen it pushed the player down, so a 16:9 movie rendered letterboxed under a grey bar
 *    instead of filling the display.
 *
 * Pure, so it tests without Compose, a player, or an activity.
 */
object UpdateBannerVisibilityPolicy {
    /**
     * @param hasUpdate an update is available to announce.
     * @param dialogRequested the updater controller currently wants the banner shown.
     * @param immersivePlaybackActive a full-bleed video surface owns the screen (player route or
     *   the docked Live TV screen). See `ImmersivePlaybackGate`.
     * @param inPictureInPicture the activity is in PiP, so the window is only a few hundred pixels
     *   wide and every row of chrome is stolen from the picture.
     */
    fun mayOccupyLayout(
        hasUpdate: Boolean,
        dialogRequested: Boolean,
        immersivePlaybackActive: Boolean,
        inPictureInPicture: Boolean,
    ): Boolean {
        if (!hasUpdate || !dialogRequested) return false
        // Checked before immersive playback on purpose: PiP can outlive the composable that set the
        // gate, and a banner in a PiP window is never acceptable.
        if (inPictureInPicture) return false
        if (immersivePlaybackActive) return false
        return true
    }
}
