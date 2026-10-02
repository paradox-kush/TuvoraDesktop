package com.nuvio.app.features.player

/**
 * Whether the player's controls shell (top/bottom scrims, the one-shot readouts and — when
 * [controlsVisible] — the controls themselves) is composed at all.
 *
 * The one-shot readouts (parental guide, stream info) live inside the shell, so the shell must
 * stay up for as long as either is playing. Otherwise the controls' auto-hide (3.5 s) tears the
 * readout out of composition mid-animation, its completion callback never fires, and it replays
 * from the top every time the user taps to reveal the controls (B105: the stream info then sat
 * over the top bar on every reveal). Locked controls and picture-in-picture hide everything.
 */
internal object PlayerChromeVisibilityPolicy {
    fun shellVisible(
        controlsVisible: Boolean,
        showParentalGuide: Boolean,
        showStreamInfo: Boolean,
        controlsLocked: Boolean,
        inPictureInPicture: Boolean,
    ): Boolean = (controlsVisible || showParentalGuide || showStreamInfo) && !controlsLocked && !inPictureInPicture
}
