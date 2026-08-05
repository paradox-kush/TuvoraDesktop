package com.nuvio.app.features.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthEligibilityTest {
    @Test
    fun signUpRequiresExplicitEligibilityConfirmation() {
        assertFalse(
            canSubmitAuth(
                isSignUp = true,
                email = "adult@example.com",
                password = "secret1",
                isLoading = false,
                eligibilityConfirmed = false,
            ),
        )
        assertTrue(
            canSubmitAuth(
                isSignUp = true,
                email = "adult@example.com",
                password = "secret1",
                isLoading = false,
                eligibilityConfirmed = true,
            ),
        )
    }

    @Test
    fun signInDoesNotRequireSignUpConfirmation() {
        assertTrue(
            canSubmitAuth(
                isSignUp = false,
                email = "user@example.com",
                password = "secret1",
                isLoading = false,
                eligibilityConfirmed = false,
            ),
        )
    }

    @Test
    fun termsUrlUsesCurrentTuvoraDomain() {
        assertEquals("https://tuvora.co/terms", TUVORA_TERMS_URL)
    }
}
