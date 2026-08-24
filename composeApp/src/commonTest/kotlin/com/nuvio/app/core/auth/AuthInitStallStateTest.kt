package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Startup must never hang on the loading gate. Two real stalls leave [AuthState] stuck in
 * [AuthState.Loading] with nothing else to move it: an anonymous / zero-profile viewer whose avatar
 * cache is warm (the Supabase client is never built, so the sync backend never flips isLoaded), and
 * an offline cold start (supabase-kt retries the boot token refresh indefinitely). The stall
 * watchdog surfaces whatever the device already knows so the UI leaves the spinner; the background
 * collector overwrites it once the real state settles. This pins the pure fallback decision.
 */
class AuthInitStallStateTest {

    private val fullAccount = AuthState.Authenticated(
        userId = "60dd7cfc-b68e-478d-b794-80fcf57a2530",
        email = "viewer@example.com",
        isAnonymous = false,
    )

    @Test
    fun `a stalled anonymous init surfaces the anonymous account instead of hanging`() {
        val next = AuthRepository.authStateAfterInitStall(
            current = AuthState.Loading,
            anonymousUserId = "anon-8f2c",
            persistedUserId = null,
            persistedEmail = null,
        )

        assertEquals(
            AuthState.Authenticated(userId = "anon-8f2c", email = null, isAnonymous = true),
            next,
            "the zero-profile 'Continue Without Account' viewer must leave the loading spinner",
        )
    }

    @Test
    fun `a stalled init with a persisted full account surfaces it`() {
        val next = AuthRepository.authStateAfterInitStall(
            current = AuthState.Loading,
            anonymousUserId = null,
            persistedUserId = fullAccount.userId,
            persistedEmail = fullAccount.email,
        )

        assertEquals(fullAccount, next, "an offline cold start keeps the cached account usable")
    }

    @Test
    fun `a stalled init with nothing persisted falls back to signed-out`() {
        val next = AuthRepository.authStateAfterInitStall(
            current = AuthState.Loading,
            anonymousUserId = null,
            persistedUserId = null,
            persistedEmail = null,
        )

        assertEquals(
            AuthState.Unauthenticated,
            next,
            "no anonymous id and no persisted session means show sign-in, not spin forever",
        )
    }

    @Test
    fun `an already-settled state is never overridden by the stall fallback`() {
        val next = AuthRepository.authStateAfterInitStall(
            current = fullAccount,
            anonymousUserId = "anon-should-be-ignored",
            persistedUserId = null,
            persistedEmail = null,
        )

        assertEquals(fullAccount, next, "the watchdog only rescues a state still stuck in Loading")
    }

    @Test
    fun `a blank anonymous id is ignored - not treated as an anonymous account`() {
        val next = AuthRepository.authStateAfterInitStall(
            current = AuthState.Loading,
            anonymousUserId = "",
            persistedUserId = fullAccount.userId,
            persistedEmail = fullAccount.email,
        )

        assertEquals(fullAccount, next, "an empty id is missing data, so the persisted account wins")
    }

    @Test
    fun `an anonymous id wins over a persisted full session`() {
        val next = AuthRepository.authStateAfterInitStall(
            current = AuthState.Loading,
            anonymousUserId = "anon-8f2c",
            persistedUserId = fullAccount.userId,
            persistedEmail = fullAccount.email,
        )

        assertEquals(
            AuthState.Authenticated(userId = "anon-8f2c", email = null, isAnonymous = true),
            next,
            "an anonymous session is checked first, matching initialize()'s own precedence",
        )
    }
}
