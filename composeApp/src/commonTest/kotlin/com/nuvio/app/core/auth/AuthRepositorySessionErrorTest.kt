package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRepositorySessionErrorTest {

    @Test
    fun authLookingErrorsGateTheRefreshProbe() {
        assertTrue(AuthRepository.couldBeInvalidSessionError(401, "jwt expired"))
        assertTrue(AuthRepository.couldBeInvalidSessionError(403, ""))
        assertTrue(AuthRepository.couldBeInvalidSessionError(null, "jwt malformed"))
        assertTrue(AuthRepository.couldBeInvalidSessionError(null, "user does not exist"))
        assertTrue(AuthRepository.couldBeInvalidSessionError(409, "foreign key violation on user_id"))
    }

    @Test
    fun nonAuthErrorsNeverGateTheRefreshProbe() {
        assertFalse(AuthRepository.couldBeInvalidSessionError(500, "internal server error"))
        assertFalse(AuthRepository.couldBeInvalidSessionError(null, "timeout"))
        assertFalse(AuthRepository.couldBeInvalidSessionError(429, "rate limited"))
    }

    @Test
    fun genuineInvalidSessionMarkersEject() {
        assertTrue(AuthRepository.isInvalidRefreshError(400, "invalid refresh token"))
        assertTrue(AuthRepository.isInvalidRefreshError(null, "refresh_token_not_found"))
        assertTrue(AuthRepository.isInvalidRefreshError(null, "invalid_grant"))
        assertTrue(AuthRepository.isInvalidRefreshError(null, "user not found"))
    }

    @Test
    fun bareStatusOrTransientFailuresKeepTheSession() {
        // recover-not-eject: a bare 400/401/403 without a genuine marker must NOT sign the user out
        // (a Cloudflare edge-403, a rate-limit, a lapsed access token). See supabase/auth-js#213.
        assertFalse(AuthRepository.isInvalidRefreshError(401, ""))
        assertFalse(AuthRepository.isInvalidRefreshError(403, "error 1020 access denied cloudflare"))
        assertFalse(AuthRepository.isInvalidRefreshError(400, "bad gateway"))
        assertFalse(AuthRepository.isInvalidRefreshError(null, "unable to resolve host"))
        assertFalse(AuthRepository.isInvalidRefreshError(503, "service unavailable"))
        assertFalse(AuthRepository.isInvalidRefreshError(408, "request timeout"))
    }
}
