package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/** The contract's outcome table (section 3): one mapping for preview AND redeem. */
class SetupCodeOutcomeTest {

    @Test
    fun `malformed input maps to a Problem with its sentence`() {
        val expected = mapOf(
            SetupCodeProblem.EMPTY to "Enter the setup code your provider gave you.",
            SetupCodeProblem.BAD_CHARACTERS to "That doesn't look like a setup code. Codes use letters and the numbers 2-9 only.",
            SetupCodeProblem.WRONG_LENGTH to "A code has 12 characters. Check it against the one your provider sent.",
        )
        for ((problem, text) in expected) {
            val outcome = SetupCodeOutcome.fromProblem(problem)
            assertEquals(SetupCodeOutcome.Problem(problem), outcome, "$problem state")
            assertEquals(text, outcome.message?.english, "$problem text")
        }
    }

    @Test
    fun `server error codes map to the table`() {
        val unusable = listOf("invalid_code", "not_found", "used", "already_used", "revoked", "suspended", "unavailable", "unknown", "", "anything_else")
        for (code in unusable) assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.fromErrorCode(code), "code `$code`")
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.fromErrorCode(null), "no code")
        assertEquals(SetupCodeOutcome.NeedsSignIn, SetupCodeOutcome.fromErrorCode("anonymous_not_allowed"))
        assertEquals(SetupCodeOutcome.NeedsSignIn, SetupCodeOutcome.fromErrorCode("not_authenticated"))
        assertEquals(SetupCodeOutcome.RateLimited(null), SetupCodeOutcome.fromErrorCode("rate_limited"))
        assertEquals(SetupCodeOutcome.ProfileGone, SetupCodeOutcome.fromErrorCode("profile_not_found"))
        assertEquals(SetupCodeOutcome.Expired(ProviderSupport.NONE), SetupCodeOutcome.fromErrorCode("expired"))
    }

    @Test
    fun `an expired redeem still offers the contacts a preview showed`() {
        val support = ProviderSupport(telegram = "acme_tv")
        assertEquals(SetupCodeOutcome.Expired(support), SetupCodeOutcome.fromErrorCode("expired", support))
    }

    @Test
    fun `the preview route's http answers map to the table`() {
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.fromPreviewHttp(400, "invalid_code", null))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.fromPreviewHttp(404, "not_found", null))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.fromPreviewHttp(409, "used", null))
        assertEquals(SetupCodeOutcome.Expired(ProviderSupport.NONE), SetupCodeOutcome.fromPreviewHttp(410, "expired", null))
        for (code in listOf("revoked", "suspended", "unavailable")) {
            assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.fromPreviewHttp(410, code, null), "410 $code")
        }
        assertEquals(SetupCodeOutcome.RateLimited(30), SetupCodeOutcome.fromPreviewHttp(429, "rate_limited", 30))
        assertEquals(SetupCodeOutcome.RateLimited(null), SetupCodeOutcome.fromPreviewHttp(429, null, null))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.fromPreviewHttp(503, "unknown", null))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.fromPreviewHttp(502, "unknown", null))
    }

    @Test
    fun `a 404 without a code means the feature is off and reads as neutral`() {
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.fromPreviewHttp(404, null, null))
    }

    @Test
    fun `a gateway failure with no answer about the code reads as a network problem`() {
        for (status in listOf(500, 502, 503, 504)) {
            assertEquals(SetupCodeOutcome.Network, SetupCodeOutcome.fromPreviewHttp(status, null, null), "HTTP $status")
        }
    }

    @Test
    fun `every state's sentence is the approved English`() {
        assertEquals("This code has expired. Ask your provider for a new one.", SetupCodeOutcome.Expired(ProviderSupport.NONE).message?.english)
        assertEquals("Too many tries. Wait a little while and try again.", SetupCodeOutcome.RateLimited(5).message?.english)
        assertEquals("We couldn't reach Tuvora. Check your connection and try again.", SetupCodeOutcome.Network.message?.english)
        assertEquals(
            "This code can't be used. If you've already used it, the playlist is in your account. " +
                "Otherwise ask your provider for a new code.",
            SetupCodeOutcome.Unusable.message?.english,
        )
        assertEquals("That profile no longer exists. Pick another.", SetupCodeOutcome.ProfileGone.message?.english)
        assertEquals(null, SetupCodeOutcome.NeedsSignIn.message)
    }

    @Test
    fun `analytics names are a closed vocabulary with no code in them`() {
        val names = listOf(
            SetupCodeOutcome.NeedsSignIn, SetupCodeOutcome.Expired(ProviderSupport.NONE), SetupCodeOutcome.RateLimited(1),
            SetupCodeOutcome.Network, SetupCodeOutcome.Unusable, SetupCodeOutcome.ProfileGone,
            SetupCodeOutcome.Problem(SetupCodeProblem.EMPTY),
        ).map { it.analyticsName }
        assertEquals(
            listOf("needs_sign_in", "expired", "rate_limited", "network", "unusable", "profile_gone", "problem"),
            names,
        )
    }
}
