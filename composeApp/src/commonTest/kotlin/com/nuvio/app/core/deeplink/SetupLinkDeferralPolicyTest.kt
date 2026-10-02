package com.nuvio.app.core.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A setup link opened while the profile picker is showing is held, then continues once the shell appears. */
class SetupLinkDeferralPolicyTest {
    @Test
    fun `a link while the profile picker or loader is showing is held and waits`() {
        assertEquals(LinkTiming.HOLD_AND_WAIT, SetupLinkDeferralPolicy.timing(hasGate = true, shellReady = false))
    }

    @Test
    fun `a link once the shell is on screen opens now`() {
        assertEquals(LinkTiming.OPEN_NOW, SetupLinkDeferralPolicy.timing(hasGate = true, shellReady = true))
    }

    @Test
    fun `a platform with no gate in front of the shell opens now`() {
        assertEquals(LinkTiming.OPEN_NOW, SetupLinkDeferralPolicy.timing(hasGate = false, shellReady = false))
        assertEquals(LinkTiming.OPEN_NOW, SetupLinkDeferralPolicy.timing(hasGate = false, shellReady = true))
    }

    @Test
    fun `a held link resumes when the shell appears and the code is still held`() {
        assertTrue(SetupLinkDeferralPolicy.shouldResume(deferred = true, shellReady = true, codeStillHeld = true))
    }

    @Test
    fun `a held link does not resume before the shell appears`() {
        assertFalse(SetupLinkDeferralPolicy.shouldResume(deferred = true, shellReady = false, codeStillHeld = true))
    }

    @Test
    fun `a held link past its 30 minutes or already used does not resume`() {
        assertFalse(SetupLinkDeferralPolicy.shouldResume(deferred = true, shellReady = true, codeStillHeld = false))
        assertFalse(SetupLinkDeferralPolicy.shouldResume(deferred = false, shellReady = true, codeStillHeld = true))
    }
}
