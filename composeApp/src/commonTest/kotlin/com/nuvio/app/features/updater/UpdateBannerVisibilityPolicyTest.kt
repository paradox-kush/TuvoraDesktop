package com.nuvio.app.features.updater

import com.nuvio.app.features.player.ImmersivePlaybackGate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression cover for the 2026-08-17 field report: "on Android VOD PiP only plays audio and not
 * video in small screen".
 *
 * The banner is a Column sibling of the entire app content, so while it is up it *shrinks* whatever
 * is below it. In a PiP window that meant ~60% of the frame went to the banner and the picture was
 * squeezed into a sliver. The old code was `state.showDialog && update != null` — it had no notion
 * of PiP or of a full-bleed player at all, so every case below that expects `false` would have
 * returned `true`.
 */
class UpdateBannerVisibilityPolicyTest {

    @AfterTest
    fun tearDown() {
        ImmersivePlaybackGate.resetForTest()
    }

    @Test
    fun `banner shows on an ordinary browse screen`() {
        assertTrue(
            UpdateBannerVisibilityPolicy.mayOccupyLayout(
                hasUpdate = true,
                dialogRequested = true,
                immersivePlaybackActive = false,
                inPictureInPicture = false,
            ),
            "an update should still be announced when nothing is playing full bleed",
        )
    }

    @Test
    fun `banner never takes layout space in picture-in-picture`() {
        assertFalse(
            UpdateBannerVisibilityPolicy.mayOccupyLayout(
                hasUpdate = true,
                dialogRequested = true,
                immersivePlaybackActive = false,
                inPictureInPicture = true,
            ),
            "the PiP window is a few hundred pixels wide - every row of chrome is stolen from video",
        )
    }

    @Test
    fun `banner stands down over a full bleed player`() {
        assertFalse(
            UpdateBannerVisibilityPolicy.mayOccupyLayout(
                hasUpdate = true,
                dialogRequested = true,
                immersivePlaybackActive = true,
                inPictureInPicture = false,
            ),
            "the banner pushed the player down and letterboxed a 16:9 movie under a grey bar",
        )
    }

    @Test
    fun `picture-in-picture wins even if the immersive gate was never set`() {
        // PiP can outlive the composable that set the gate, so the PiP check must not depend on it.
        assertFalse(
            UpdateBannerVisibilityPolicy.mayOccupyLayout(
                hasUpdate = true,
                dialogRequested = true,
                immersivePlaybackActive = false,
                inPictureInPicture = true,
            ),
            "PiP alone must suppress the banner",
        )
    }

    @Test
    fun `no update means no banner regardless of playback state`() {
        assertFalse(
            UpdateBannerVisibilityPolicy.mayOccupyLayout(
                hasUpdate = false,
                dialogRequested = true,
                immersivePlaybackActive = false,
                inPictureInPicture = false,
            ),
            "nothing to announce",
        )
        assertFalse(
            UpdateBannerVisibilityPolicy.mayOccupyLayout(
                hasUpdate = true,
                dialogRequested = false,
                immersivePlaybackActive = false,
                inPictureInPicture = false,
            ),
            "controller has not asked for the banner",
        )
    }

    @Test
    fun `immersive gate is reference counted across a handoff`() {
        assertFalse(ImmersivePlaybackGate.isActive.value, "starts clear")

        // Live TV is on screen, then the player route composes before Live TV leaves.
        ImmersivePlaybackGate.setImmersive(true)
        ImmersivePlaybackGate.setImmersive(true)
        assertTrue(ImmersivePlaybackGate.isActive.value, "two immersive surfaces composed")

        ImmersivePlaybackGate.setImmersive(false)
        assertTrue(
            ImmersivePlaybackGate.isActive.value,
            "the leaving screen must not clear a flag the arriving screen still needs",
        )

        ImmersivePlaybackGate.setImmersive(false)
        assertFalse(ImmersivePlaybackGate.isActive.value, "both surfaces gone")
    }

    @Test
    fun `immersive gate does not underflow`() {
        ImmersivePlaybackGate.setImmersive(false)
        ImmersivePlaybackGate.setImmersive(false)
        ImmersivePlaybackGate.setImmersive(true)
        assertEquals(
            true,
            ImmersivePlaybackGate.isActive.value,
            "an unbalanced dispose must not drive the count negative and swallow the next enter",
        )
    }
}
