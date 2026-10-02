package com.nuvio.app.core.deeplink

/** When a setup link may be routed to the preview. */
internal enum class LinkTiming { OPEN_NOW, HOLD_AND_WAIT }

/**
 * A setup link must never be lost. While the profile picker (or the launch loader) is showing there is no
 * shell to open the preview in, and routing then lands on a page that is about to be rebuilt. So the code is
 * held in memory (same holder, same 30 minute bound) and the preview opens once the shell is on screen.
 * Pure, so the decision is tested without a UI.
 */
internal object SetupLinkDeferralPolicy {
    /**
     * @param hasGate a launch overlay / profile gate can cover the shell (the runtime-owning shell on every platform).
     * @param shellReady the overlay is gone: a profile is chosen and Home has rendered.
     */
    fun timing(hasGate: Boolean, shellReady: Boolean): LinkTiming =
        if (hasGate && !shellReady) LinkTiming.HOLD_AND_WAIT else LinkTiming.OPEN_NOW

    /** Resume a deferred link: the shell appeared and the held code is still within its 30 minutes. */
    fun shouldResume(deferred: Boolean, shellReady: Boolean, codeStillHeld: Boolean): Boolean =
        deferred && shellReady && codeStillHeld
}

/** Process-wide memory flag: a setup link arrived while the shell was not ready and is waiting. Never persisted. */
internal object DeferredSetupLink {
    var pending: Boolean = false
}
