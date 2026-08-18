package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A refresh failure means supabase-kt is retrying, not that the account is gone. It must never be
 * the thing that drops a signed-in user to the sign-in screen.
 */
class AuthRefreshFailureStateTest {

    private val signedIn = AuthState.Authenticated(
        userId = "60dd7cfc-b68e-478d-b794-80fcf57a2530",
        email = "viewer@example.com",
        isAnonymous = false,
    )

    @Test
    fun `an unreadable session during a refresh failure keeps the signed-in state`() {
        val next = AuthRepository.authStateAfterRefreshFailure(
            current = signedIn,
            persistedUserId = null,
            persistedEmail = null,
        )

        assertEquals(signedIn, next, "a retrying refresh must not sign the viewer out")
    }

    @Test
    fun `a blank persisted id is treated as unreadable rather than as a sign-out`() {
        val next = AuthRepository.authStateAfterRefreshFailure(
            current = signedIn,
            persistedUserId = "",
            persistedEmail = null,
        )

        assertEquals(signedIn, next, "a blank id is missing data not a dead session")
    }

    @Test
    fun `a readable persisted session republishes the account`() {
        val next = AuthRepository.authStateAfterRefreshFailure(
            current = AuthState.Loading,
            persistedUserId = signedIn.userId,
            persistedEmail = signedIn.email,
        )

        assertEquals(signedIn, next, "the cached account should stay usable while offline")
    }

    @Test
    fun `a signed-out viewer is not promoted by a refresh failure`() {
        val next = AuthRepository.authStateAfterRefreshFailure(
            current = AuthState.Unauthenticated,
            persistedUserId = null,
            persistedEmail = null,
        )

        assertEquals(AuthState.Unauthenticated, next, "nothing to restore means nothing changes")
    }
}
