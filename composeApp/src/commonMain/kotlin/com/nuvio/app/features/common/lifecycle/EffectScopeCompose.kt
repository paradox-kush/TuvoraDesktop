package com.nuvio.app.features.common.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The sink a failed/late revert reports to. Default is a no-op so a screen composed outside the
 * wiring (a preview, an early frame) degrades quietly; FeatureWiring provides the real telemetry
 * sink at the root (Rule 1). Loud beats silent for MISSING wiring, but a failed revert is already an
 * error being reported — its default must never itself throw.
 */
val LocalRevertFailureSink = staticCompositionLocalOf<(Throwable) -> Unit> {
    { /* no-op; FeatureWiring provides the telemetry sink */ }
}

/**
 * A composition-scoped [EffectScope] that disposes (LIFO) when [keys] change or the composable
 * leaves. Hangs off DisposableEffect — the same disposal boundary the live player already uses via
 * `key(url)` at LiveTvScreen. The failure sink comes from [LocalRevertFailureSink], so call sites
 * never construct one.
 */
@Composable
fun rememberEffectScope(vararg keys: Any?): EffectScope {
    val sink = LocalRevertFailureSink.current
    val scope = remember(*keys) { EffectScope(onRevertFailure = sink) }
    DisposableEffect(scope) { onDispose { scope.dispose() } }
    return scope
}
