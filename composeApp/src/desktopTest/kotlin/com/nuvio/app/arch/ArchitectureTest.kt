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

    // Only THIS checkout's sources. Nested worktrees (wt/…) hold their own copy of composeApp/src and
    // must not be scanned twice — but the old `"/wt/" !in path` filter also dropped EVERY file when the
    // checkout itself lives under wt/, so the whole test passed vacuously in any worktree. Keep the
    // files whose checkout prefix is the shortest one seen (the project's own).
    private val files: List<Pair<String, String>> = run {
        val sources = Konsist.scopeFromProject().files
            .map { it.path to it.text }
            .filter { (p, _) -> "/composeApp/src/" in p }
        val checkout = sources.map { (p, _) -> p.substringBefore("/composeApp/src/") }.minByOrNull { it.length }
        sources.filter { (p, _) -> p.substringBefore("/composeApp/src/") == checkout }
    }

    // --- fork-side definition (upstream absence, not directory naming) ---
    private val forkPaths = listOf(
        "/features/radar/", "/features/iptv/", "/features/epg/", "/features/livetv/", "/features/dev/",
        "/features/announcements/",
        // Media servers (Jellyfin/Emby): fork-owned from day one, so the firewall already holds when the
        // first file lands - shared code reaches it only through core/contracts ports.
        "/features/mediaserver/",
        "/core/analytics/", "/core/diag/", "/core/memory/", "/core/rec/",
    )
    private val forkFiles = listOf("ImmersivePlaybackGate.kt")
    private fun isForkFile(path: String) =
        forkPaths.any { path.contains(it) } || forkFiles.any { path.endsWith("/$it") }
    private fun isWiringFile(path: String) =
        path.endsWith("/com/nuvio/app/FeatureWiring.kt") ||
        // Logic-port half of the composition root, split out so Apple TV (:tvosCore) shares the list.
        path.endsWith("/com/nuvio/app/FeatureContributions.kt") ||
        path.endsWith("/com/nuvio/app/AndroidFeatureWiring.kt")

    // fork FEATURE refs (R2b) + fork-only core SUBSYSTEM refs (R2d — rec+memory get ports;
    // analytics+diag are DELIBERATELY EXEMPT: cross-cutting telemetry, accepted as thin diff).
    private val forkRef = Regex("""\bcom\.nuvio\.app\.features\.(radar|iptv|epg|livetv|dev|announcements|mediaserver)\.""")
    private val forkCoreRef = Regex("""\bcom\.nuvio\.app\.core\.(rec|memory)\.""")

    // Strip block + WHOLE-LINE // comments only. A naive //.* eats the // in "https://…" literals and
    // silently disables enforcement for that line (an invisible false NEGATIVE).
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)^\s*//.*$"""), "")

    private fun rel(path: String) = path.substringAfter("/composeApp/src/")

    @Test
    fun `the scan covers this checkout's sources`() {
        // Guard against a vacuous pass: every rule below iterates [files].
        assertTrue(files.size > 200, "scanned only ${files.size} source files")
        assertTrue(files.any { (p, _) -> isWiringFile(p) }, "composition root not in the scan")
    }

    @Test
    fun `the fork set names the media-server feature (Wave 3 P0)`() {
        // The lists are explicit (fork side = upstream absence), so a new fork feature must be ADDED to
        // both before its first file lands, or shared code could reference it unchecked. The probes are
        // built by concatenation: this file is scanned by the rules below and must not itself contain a
        // fork FQN.
        val feature = "com.nuvio.app." + "features.mediaserver."
        assertTrue(isForkFile("/composeApp/src/commonMain/kotlin/com/nuvio/app/features/mediaserver/internal/X.kt"))
        assertTrue(forkRef.containsMatchIn("import " + feature + "api.MediaServerFeature"))
        assertTrue(forkRef.containsMatchIn("\"" + feature + "internal.Client\""), "FQNs inside string literals count")
        assertTrue(!forkRef.containsMatchIn("com.nuvio.app.core.contracts.OwnSourcePolicy"), "neutral ports stay reachable")
    }

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
