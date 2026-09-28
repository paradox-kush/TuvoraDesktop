package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android's LocalUriHandler rethrows when no activity handles ACTION_VIEW (no browser), and the
 * desktop JVM's Desktop.browse throws when there is no default browser (common on Linux).
 * [ExternalLinkPolicy] tries the platform opener and turns any refusal into a copy-the-link
 * fallback — never a crash.
 */
class ExternalLinkPolicyTest {

    private val url = "https://tuvora.co/privacy"

    /** Stand-in for Compose's IllegalArgumentException("Can't open …") wrapping ActivityNotFoundException. */
    private class NoActivityFound : IllegalArgumentException("Can't open https://tuvora.co/privacy.")

    /** Stand-in for java.io.IOException from Desktop.browse — a checked, non-Runtime exception. */
    private class NoDefaultBrowser : Exception("No application is associated with the URL")

    @Test
    fun `a working browser opens the link`() {
        val launched = mutableListOf<String>()
        val outcome = ExternalLinkPolicy.open(url) { launched += it }
        assertEquals(ExternalLinkPolicy.Outcome.Opened, outcome)
        assertEquals(listOf(url), launched)
    }

    @Test
    fun `no browser on Android falls back to copying the link`() {
        val outcome = ExternalLinkPolicy.open(url) { throw NoActivityFound() }
        assertEquals(ExternalLinkPolicy.Outcome.CopyFallback(url), outcome)
    }

    @Test
    fun `no default browser on desktop falls back to copying the link`() {
        val outcome = ExternalLinkPolicy.open(url) { throw NoDefaultBrowser() }
        assertEquals(ExternalLinkPolicy.Outcome.CopyFallback(url), outcome)
    }

    @Test
    fun `an unsupported platform opener also falls back`() {
        val outcome = ExternalLinkPolicy.open(url) { throw UnsupportedOperationException("BROWSE") }
        assertEquals(ExternalLinkPolicy.Outcome.CopyFallback(url), outcome)
    }

    @Test
    fun `a blank url is ignored without calling the opener`() {
        var called = false
        val outcome = ExternalLinkPolicy.open("   ") { called = true }
        assertEquals(ExternalLinkPolicy.Outcome.Ignored, outcome)
        assertTrue(!called)
    }

    @Test
    fun `the url is trimmed before opening`() {
        val launched = mutableListOf<String>()
        ExternalLinkPolicy.open("  $url  ") { launched += it }
        assertEquals(listOf(url), launched)
    }
}
