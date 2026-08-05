package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule every "local or server?" branch hangs off, pinned because getting it wrong is silent.
 *
 * `isAnonymous` reads like the right check and is not: it is false for [AuthState.Unauthenticated]
 * and [AuthState.Loading] as well as for real accounts. Branching on `!isAnonymous` therefore sends
 * a session-less client down the server path, where the RPC goes out with the anon key as its
 * bearer and Postgres answers `42501 permission denied for function sync_push_watch_progress` —
 * every `sync_push_*` is explicitly revoked from `anon`. Nothing crashes; the write is just
 * dropped, which is how watch progress went missing without a single visible failure.
 */
class AuthStateLocalOnlyTest {

    @Test
    fun aRealAccountIsTheOnlyStateThatMayTalkToTheServer() {
        val realAccount = AuthState.Authenticated(userId = "u1", email = "a@b.c", isAnonymous = false)
        assertFalse(realAccount.isLocalOnly)
    }

    @Test
    fun everyOtherStateIsLocalOnly() {
        assertTrue(AuthState.Unauthenticated.isLocalOnly, "no session — RPCs would go out as anon")
        assertTrue(AuthState.Loading.isLocalOnly, "session not resolved yet — same exposure")
        assertTrue(
            AuthState.Authenticated(userId = "u2", email = null, isAnonymous = true).isLocalOnly,
            "an anonymous account's data must stay on-device",
        )
    }

    @Test
    fun isAnonymousIsNotASubstituteForIsLocalOnly() {
        // The trap, stated directly: these two states are NOT anonymous, yet both must stay local.
        assertFalse(AuthState.Unauthenticated.isAnonymous)
        assertFalse(AuthState.Loading.isAnonymous)
        assertTrue(AuthState.Unauthenticated.isLocalOnly)
        assertTrue(AuthState.Loading.isLocalOnly)
    }
}
