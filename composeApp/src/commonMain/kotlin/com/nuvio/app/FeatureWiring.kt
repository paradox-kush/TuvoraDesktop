package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import co.touchlab.kermit.Logger
import com.nuvio.app.features.common.lifecycle.FeatureRegistry
import com.nuvio.app.features.common.lifecycle.LocalRevertFailureSink

/**
 * THE one firewall exception (rules doc Rule 1 / R2b): the only non-fork file allowed to name fork
 * implementations, because KMP commonMain has no classpath auto-discovery (no ServiceLoader /
 * reflection) — something must statically reference the fork registrations, and this is that
 * something. It is the permanent, single allowlist entry in the architecture test, and is fork-only
 * in practice (absent upstream) so it carries zero merge cost.
 *
 * Two wiring points, deliberately separate:
 *   - [registerFeatureContributions] — PROCESS-INIT (contributions + ports registered once per
 *     process), called from the platform entry point.
 *   - [installFeatures] — COMPOSITION (provides the ports as CompositionLocals to the UI tree).
 *
 * Phase 0 status: no feature ports exist yet (S3a defines the first). This file lands as the
 * structure + the loud init guard, so the bootstrap is proven before any port is built on it. Each
 * later seam adds its registration/provision line here — do NOT invent ports to fill it.
 */

private val revertLog = Logger.withTag("EffectScope")

/**
 * Called ONCE per PROCESS from a genuinely once-per-process entry point:
 *   Android → `NuvioApplication.onCreate` (NOT `MainActivity.onCreate`, which re-runs on every
 *     configuration-change recreation in the same process → duplicate registration → crash on rotate);
 *   iOS → the app's `@main` bootstrap (NOT a view-controller factory — controllers recreate);
 *   Desktop → `main` before the window opens.
 * NEVER call from a @Composable body (recomposition re-runs it — same crash class).
 */
fun registerFeatureContributions() {
    // Phase 0: no contributions/ports yet. The bootstrap + guard exist so later seams plug in safely.
    FeatureRegistry.markInitialized()
}

/**
 * Wraps the app content, providing the fork ports to the UI tree. Provisioning ONLY — never
 * registration (that is process-init, above). The [check] makes a forgotten app-init call a loud
 * startup error instead of silently emptying every registry.
 */
@Composable
fun installFeatures(content: @Composable () -> Unit) {
    check(FeatureRegistry.isInitialized) {
        "registerFeatureContributions() was not called at app init — see FeatureWiring.kt"
    }
    CompositionLocalProvider(
        LocalRevertFailureSink provides ::onRevertFailure,
        // Phase 0: no feature ports yet. `LocalIptvCatalog provides IptvFeature.provideCatalog()`,
        // `LocalSportsData provides RadarFeature.provideSportsData()`, … land with their seams.
    ) { content() }
}

/**
 * Previews and UI tests never run app-init, so they cannot use [installFeatures] (its [check] would
 * throw). This provides the ports as fakes and bypasses the guard. It lives in THIS file on purpose:
 * it names fork api types too, so any other home would need a second firewall exemption, eroding the
 * one-exception invariant. Phase 0 has no ports, so it currently only supplies the revert sink.
 */
@Composable
fun PreviewFeatureWiring(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalRevertFailureSink provides { /* no-op in previews */ },
    ) { content() }
}

/**
 * Telemetry-at-the-disposal-boundary (design G4 / S9). Phase 0 logs; S9 additionally captures a
 * telemetry event so a failed/late release becomes a measured signal instead of a silent leak.
 */
private fun onRevertFailure(t: Throwable) {
    revertLog.w(t) { "revert failed during teardown" }
}
