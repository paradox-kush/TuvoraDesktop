package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Watch party status chip in the desktop native controls (player-ui/controls.html + .css). On the phone the pill's
 * "Waiting for..." / "Reconnecting" line stays on screen when the controls auto-hide; the desktop chip must too, so it
 * cannot live inside the header actions, which `.player.chrome-hidden` fades out.
 */
class NativeControlsPartyStatusTest {
    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), path).readText()

    private val html = resource("player-ui/controls.html")
    private val css = resource("player-ui/controls.css")

    @Test
    fun `the party status chip is not inside the auto-hiding header actions`() {
        val headerActions = Regex("""<nav class="header-actions"[\s\S]*?</nav>""").find(html)?.value
        assertNotNull(headerActions, "header actions block")
        assertTrue("id=\"partyStatus\"" in html, "the chip exists")
        assertFalse("id=\"partyStatus\"" in headerActions, "a chip inside .header-actions hides with the chrome")
    }

    @Test
    fun `no chrome-hidden or native-resizing rule hides the party status chip`() {
        // Every selector list that targets the hidden chrome state.
        val hidingRules = Regex("""([^{}]*\.player\.chrome-hidden[^{}]*)\{""").findAll(css).map { it.groupValues[1] }.toList()
        assertTrue(hidingRules.isNotEmpty(), "the chrome-hidden rules exist")
        hidingRules.forEach { selectors -> assertFalse("party-status" in selectors, selectors.trim()) }
    }
}
