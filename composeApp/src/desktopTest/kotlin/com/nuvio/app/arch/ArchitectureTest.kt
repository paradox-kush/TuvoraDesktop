package com.nuvio.app.arch

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Architecture enforcement (rules doc Rule 6) — the mechanism that makes the design stick. JVM-only:
 * Konsist is a static source scan, so it runs once on the host test set, not per platform.
 *
 * Fork side = UPSTREAM-ABSENT paths (verified via `git cat-file -e origin/cmp-rewrite:<path>`), NOT a
 * features/ name list: fork-only code also lives under core/{analytics,diag,memory,rec} and as a few
 * files inside shared dirs. RE-VERIFY the fork set at every upstream sync.
 *
 * Baseline-and-ratchet: ~200 pre-existing objects + 26 crossing files cannot be greened today, so the
 * baseline in [ArchBaseline] freezes them; the test fails only on NEW violations and the baseline only
 * shrinks (each seam deletes its entries). A wrong fork set bakes wrong entries into the baseline —
 * and baseline entries are forever — which is why the set is computed, not guessed.
 */
class ArchitectureTest {

    private val files: List<Pair<String, String>> =
        Konsist.scopeFromProject().files
            .map { it.path to it.text }
            .filter { (p, _) -> "/composeApp/src/" in p && "/wt/" !in p }

    // --- fork-side definition (upstream absence, not directory naming) ---
    private val forkPaths = listOf(
        "/features/radar/", "/features/iptv/", "/features/epg/", "/features/livetv/", "/features/dev/",
        "/core/analytics/", "/core/diag/", "/core/memory/", "/core/rec/",
    )
    private val forkFiles = listOf("ImmersivePlaybackGate.kt")
    private fun isForkFile(path: String) =
        forkPaths.any { path.contains(it) } || forkFiles.any { path.endsWith("/$it") }
    private fun isWiringFile(path: String) = path.endsWith("/com/nuvio/app/FeatureWiring.kt")

    // fork FEATURE refs (R2b) + fork-only core SUBSYSTEM refs (R2d — rec+memory get ports;
    // analytics+diag are DELIBERATELY EXEMPT: cross-cutting telemetry, accepted as thin diff).
    private val forkRef = Regex("""\bcom\.nuvio\.app\.features\.(radar|iptv|epg|livetv|dev)\.""")
    private val forkCoreRef = Regex("""\bcom\.nuvio\.app\.core\.(rec|memory)\.""")

    // Strip block + WHOLE-LINE // comments only. A naive //.* eats the // in "https://…" literals and
    // silently disables enforcement for that line (an invisible false NEGATIVE).
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)^\s*//.*$"""), "")

    private fun rel(path: String) = path.substringAfter("/composeApp/src/")

    @Test
    fun `non-fork code never references a fork feature or fork-only core subsystem (R2b + R2d)`() {
        val violations = files
            .filter { (p, _) -> !isForkFile(p) && !isWiringFile(p) }
            .filter { (_, text) ->
                val code = stripComments(text)
                forkRef.containsMatchIn(code) || forkCoreRef.containsMatchIn(code)
            }
            .map { (p, _) -> rel(p) }
            .filterNot { it in ArchBaseline.crossings }
            .sorted()
        assertTrue(
            violations.isEmpty(),
            "NEW firewall crossing(s) — cross via an extension point (design §7), not a direct reference:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `features are reached only through their api package (R2a)`() {
        val internalRef = Regex("""\bcom\.nuvio\.app\.features\.([a-z]+)\.internal\.""")
        val violations = files
            .filter { (p, text) ->
                internalRef.findAll(stripComments(text)).any { m ->
                    m.groupValues[1] != "common" && "/features/${m.groupValues[1]}/" !in p
                }
            }
            .map { (p, _) -> rel(p) }
            .filterNot { it in ArchBaseline.crossings }
            .sorted()
        assertTrue(
            violations.isEmpty(),
            "cross-feature internal access — go through the feature's api package:\n" +
                violations.joinToString("\n"),
        )
    }
}
