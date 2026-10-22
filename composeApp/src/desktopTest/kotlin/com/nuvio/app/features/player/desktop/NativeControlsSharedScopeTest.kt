package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The native controls page loads loading-indicator.js, party.js and controls.js as classic scripts, so their top-level
 * `const`/`let`/`function` names share one scope, and controls.html has one DOM. A textual merge of two independent
 * additions (the fork's party entry button and upstream's in-controls party UI, sync 14) produced a second
 * `const partyButton` and a second `id="partyButton"`: a SyntaxError that would have killed the whole control layer
 * without any compile error. Pin both.
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
}
