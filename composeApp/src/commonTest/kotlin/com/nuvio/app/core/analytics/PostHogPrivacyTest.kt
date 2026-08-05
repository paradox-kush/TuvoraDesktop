package com.nuvio.app.core.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostHogPrivacyTest {

    @Test
    fun dropsDeepLinkEventsRegardlessOfCasing() {
        assertTrue(PostHogPrivacy.shouldDropEvent("Deep Link Opened"))
        assertTrue(PostHogPrivacy.shouldDropEvent("deep link opened"))
        assertFalse(PostHogPrivacy.shouldDropEvent("app_exit"))
    }

    @Test
    fun removesSensitiveKeysAndNestedAuthValuesWithoutLosingDiagnostics() {
        val sanitized = PostHogPrivacy.sanitize(
            mapOf(
                "reason" to "anr",
                "reason_code" to 6,
                "url" to "nuvio://auth/trakt?code=secret&state=nonce",
                "detail" to "GET https://panel.example/live?token=secret failed",
                "nested" to mapOf("authorization" to "Bearer secret", "phase" to "matching"),
            ),
        )

        assertEquals("anr", sanitized["reason"])
        assertEquals(6, sanitized["reason_code"])
        assertFalse("url" in sanitized)
        assertEquals("GET [redacted-url] failed", sanitized["detail"])
        assertEquals(mapOf("phase" to "matching"), sanitized["nested"])
        assertEquals(true, sanitized[PostHogPrivacy.GEOIP_DISABLE_PROPERTY])
    }

    @Test
    fun redactsAuthFragmentsWithoutAFullUrl() {
        val sanitized = PostHogPrivacy.sanitize(
            mapOf(
                "message" to "callback failed: code=abc123&state=xyz789",
                "network" to "rtsp://user:pass@provider.example/live failed with Bearer abc.def",
            ),
        )

        assertEquals(
            "callback failed: code=[redacted]&state=[redacted]",
            sanitized["message"],
        )
        assertEquals("[redacted-url] failed with [redacted-auth]", sanitized["network"])
    }
}
