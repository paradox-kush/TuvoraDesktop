package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import com.nuvio.app.features.player.PlayerControlPartyState
import com.nuvio.app.features.player.PlayerControlsState
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The native controls page loads loading-indicator.js, party.js and controls.js as classic scripts, so their top-level
 * `const`/`let`/`function` names share one scope, and controls.html has one DOM. A textual merge of two independent
 * additions (the fork's party entry button and upstream's in-controls party UI, sync 14) produced a second
 * `const partyButton` and a second `id="partyButton"`: a SyntaxError that would have killed the whole control layer
 * without any compile error. Pin both. (After the sync-14 follow-up the fork's own party UI is retired, so only upstream's party.js remains.)
 */
class NativeControlsSharedScopeTest {
    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), path).readText()

    @Test
    fun `controls html has no duplicate element ids`() {
        val ids = Regex("""\sid="([^"]+)"""").findAll(resource("player-ui/controls.html")).map { it.groupValues[1] }.toList()
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(emptySet<String>(), duplicates, "duplicate ids in controls.html")
    }

    @Test
    fun `the control scripts declare each top-level name once`() {
        val declaration = Regex("""^(?:const|let|function)\s+([A-Za-z_$][\w$]*)""", RegexOption.MULTILINE)
        val names = listOf("loading-indicator.js", "party.js", "controls.js")
            .flatMap { file -> declaration.findAll(resource("player-ui/$file")).map { it.groupValues[1] to file }.toList() }
        val duplicates = names.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        assertEquals(emptyMap<String, List<String>>(), duplicates, "names declared twice across the shared script scope")
    }

    @Test
    fun `the fork's retired party entry and status line are gone`() {
        // Upstream's party.js owns the entry button (partyButton), pill and panel; the fork's window UI was retired.
        val html = resource("player-ui/controls.html")
        val js = resource("player-ui/controls.js")
        listOf("partyEntryButton", "partyStatus").forEach { name ->
            assertEquals(false, name in html, "$name still in controls.html")
            assertEquals(false, name in js, "$name still in controls.js")
        }
    }

    @Test
    fun `the party pill stays up when the controls auto-hide`() {
        // Phone parity: "Waiting for...", "Reconnecting" and the behind line must survive the chrome fading out.
        val css = resource("player-ui/controls.css") + resource("player-ui/party.css")
        val hidingRules = Regex("""([^{}]*\.player\.chrome-hidden[^{}]*)\{""").findAll(css).map { it.groupValues[1] }.toList()
        assertTrue(hidingRules.isNotEmpty(), "the chrome-hidden rules exist")
        hidingRules.forEach { selectors -> assertFalse("party-pill" in selectors, selectors.trim()) }
        val js = resource("player-ui/party.js")
        assertTrue("(state.controlsVisible || party.pillPinned)" in js, "pill visibility follows pillPinned, not only the chrome")
    }

    @Test
    fun `chat is hidden until the backend answers`() {
        val js = resource("player-ui/party.js")
        assertTrue("partyTabs.hidden = !active || !chatAvailable" in js, "the chat tab is gated on chat.available")
        val json = PlayerControlsState(party = PlayerControlPartyState()).toControlsJson(isFullscreen = false)
        assertTrue("\"chat\":{\"available\":false" in json, "controls JSON carries chat.available=false by default")
    }
}
