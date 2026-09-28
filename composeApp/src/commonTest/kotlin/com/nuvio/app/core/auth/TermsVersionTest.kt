package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class TermsVersionTest {
    @Test
    fun `sign-up records the current terms version`() {
        assertEquals("2026-09-27", TUVORA_TERMS_VERSION)
    }
}
