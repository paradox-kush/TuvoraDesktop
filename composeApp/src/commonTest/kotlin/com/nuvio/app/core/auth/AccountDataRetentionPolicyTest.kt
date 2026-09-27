package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D1: a session that disappears without the user asking (failed refresh, rejected token, a stored
 * session that is simply gone) used to erase every profile's local data, including watch progress
 * that had never synced. Only a deliberate end may wipe; a lost session keeps everything and asks
 * the viewer to sign in again.
 */
class AccountDataRetentionPolicyTest {

    private val owner = LocalDataOwner(userId = "60dd7cfc-b68e-478d-b794-80fcf57a2530", email = "viewer@example.com")

    @Test
    fun `a lost session never wipes local data`() {
        assertFalse(
            AccountDataRetentionPolicy.wipesLocalData(SessionEndReason.SESSION_LOST),
            "an unexpected session loss must keep every profile's data",
        )
    }

    @Test
    fun `every deliberate end still wipes local data`() {
        listOf(
            SessionEndReason.USER_SIGN_OUT,
            SessionEndReason.ACCOUNT_DELETED,
            SessionEndReason.SYNC_BACKEND_SWITCH,
            SessionEndReason.SWITCHED_ACCOUNT,
        ).forEach { reason ->
            assertTrue(AccountDataRetentionPolicy.wipesLocalData(reason), "$reason is deliberate and must wipe")
        }
    }

    @Test
    fun `an unexpected session loss keeps unsynced watch progress`() {
        // Four device-only items, the exact shape of the emulator run that lost them for good.
        val unsyncedProgress = mutableListOf("tt0111161", "tt0068646", "tt0468569", "tt0071562")
        var noticeShown = false

        val wiped = endAccountSession(
            reason = SessionEndReason.SESSION_LOST,
            clearLocalData = { unsyncedProgress.clear() },
            onSessionLost = { noticeShown = true },
        )

        assertFalse(wiped, "a lost session must report that nothing was wiped")
        assertEquals(4, unsyncedProgress.size, "unsynced progress must survive a lost session")
        assertTrue(noticeShown, "a lost session must ask the viewer to sign in again")
    }

    @Test
    fun `a deliberate sign-out clears local data without the lost-session notice`() {
        val progress = mutableListOf("tt0111161")
        var noticeShown = false

        val wiped = endAccountSession(
            reason = SessionEndReason.USER_SIGN_OUT,
            clearLocalData = { progress.clear() },
            onSessionLost = { noticeShown = true },
        )

        assertTrue(wiped, "a deliberate sign-out wipes")
        assertTrue(progress.isEmpty(), "a deliberate sign-out clears local data")
        assertFalse(noticeShown, "a deliberate sign-out is not a surprise and shows no notice")
    }

    @Test
    fun `signing in with no recorded owner proceeds`() {
        assertEquals(
            SignInDataDecision.PROCEED,
            AccountDataRetentionPolicy.decideOnSignIn(owner = null, signedInUserId = owner.userId),
            "a fresh device or one wiped by a deliberate sign-out has no other account's data",
        )
    }

    @Test
    fun `signing back in to the same account proceeds so pending changes sync`() {
        assertEquals(
            SignInDataDecision.PROCEED,
            AccountDataRetentionPolicy.decideOnSignIn(owner = owner, signedInUserId = owner.userId),
            "the same account must pick its kept data back up",
        )
    }

    @Test
    fun `signing in to a different account asks before touching the kept data`() {
        assertEquals(
            SignInDataDecision.ASK_BEFORE_REPLACING_OTHER_ACCOUNT_DATA,
            AccountDataRetentionPolicy.decideOnSignIn(owner = owner, signedInUserId = "b0b0b0b0-0000-4000-8000-000000000000"),
            "one person's kept data must never be merged into another person's account",
        )
    }

    @Test
    fun `a blank recorded owner is treated as no owner`() {
        assertEquals(
            SignInDataDecision.PROCEED,
            AccountDataRetentionPolicy.decideOnSignIn(owner = LocalDataOwner(userId = " ", email = null), signedInUserId = owner.userId),
            "a corrupt owner record must not block sign-in",
        )
    }

    @Test
    fun `the lost-session notice shows only while signed out with kept data`() {
        assertTrue(
            AccountDataRetentionPolicy.showsSessionLostNotice(isSignedOut = true, owner = owner, acknowledged = false),
            "signed out with another session's data kept: tell the viewer",
        )
        assertFalse(
            AccountDataRetentionPolicy.showsSessionLostNotice(isSignedOut = true, owner = owner, acknowledged = true),
            "once acknowledged the notice does not nag on every launch",
        )
        assertFalse(
            AccountDataRetentionPolicy.showsSessionLostNotice(isSignedOut = true, owner = null, acknowledged = false),
            "a device that was never signed in (or was deliberately signed out) has nothing to warn about",
        )
        assertFalse(
            AccountDataRetentionPolicy.showsSessionLostNotice(isSignedOut = false, owner = owner, acknowledged = false),
            "a signed-in viewer needs no sign-in notice",
        )
    }
}
