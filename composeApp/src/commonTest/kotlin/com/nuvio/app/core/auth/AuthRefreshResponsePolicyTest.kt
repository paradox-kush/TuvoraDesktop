package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression cover for the sign-out-after-a-blip bug: supabase-kt clears the persisted session for
 * any refresh failure outside its 5xx retry set, so a 429/408/Cloudflare-403 used to destroy a
 * valid session. These responses must be retried at the HTTP layer instead.
 */
class AuthRefreshResponsePolicyTest {

    @Test
    fun `rate limited refresh is retried rather than treated as a dead session`() {
        assertTrue(retryFor(statusCode = 429))
        assertTrue(retryFor(statusCode = 408))
    }

    @Test
    fun `cloudflare edge block on the refresh endpoint is retried`() {
        assertTrue(retryFor(statusCode = 403, cloudflareRay = "8f2a1c9d0e00-DFW"))
        assertTrue(retryFor(statusCode = 403, server = "cloudflare"))
    }

    @Test
    fun `a plain 403 from gotrue is not retried`() {
        assertFalse(retryFor(statusCode = 403))
    }

    @Test
    fun `only the refresh grant on the token endpoint is retried`() {
        assertFalse(retryFor(statusCode = 429, grantType = "password"))
        assertFalse(retryFor(statusCode = 429, path = "/rest/v1/profiles"))
    }

    @Test
    fun `a real invalid-token body is never retried`() {
        assertFalse(retryFor(statusCode = 400))
        assertTrue(isInvalidAuthRefreshResponse(400, "refresh_token_not_found"))
        assertTrue(isInvalidAuthRefreshResponse(401, "Invalid Refresh Token"))
    }

    @Test
    fun `an edge block is not mistaken for an invalid session`() {
        assertFalse(isInvalidAuthRefreshResponse(403, "Cloudflare request blocked"))
        assertFalse(isInvalidAuthRefreshResponse(429, "refresh_token_not_found"))
    }

    private fun retryFor(
        statusCode: Int,
        path: String = "/auth/v1/token",
        grantType: String? = "refresh_token",
        server: String? = null,
        cloudflareRay: String? = null
    ): Boolean = shouldRetryAuthRefreshResponse(
        statusCode = statusCode,
        path = path,
        grantType = grantType,
        server = server,
        cloudflareRay = cloudflareRay
    )
}
