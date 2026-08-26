package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

internal enum class AppVisibility {
    Foreground,
    Background,
}

internal expect object AppForegroundMonitor {
    fun events(): Flow<AppVisibility>
}

/**
 * Only a return-to-foreground may drive a foreground refresh/pull.
 *
 * The monitor's contract grew a `Background` case in the `Flow<Unit>` -> `Flow<AppVisibility>`
 * refactor (a merge-resolution carry-over), but both `events()` consumers still collected every
 * emission. That silently degraded cross-device sync on 1.5.7 (all platforms; worst on iOS, where
 * the monitor emits `Background` on every resign/suspend): a pull kicked off as the app backgrounds
 * fails (network torn / token flap) and arms `SyncManager`'s per-profile retry-backoff, which then
 * suppresses the NEXT, legitimate foreground pull — `requestForegroundPull(force = true)` bypasses
 * the recency throttle but NOT `isInRetryBackoff`. Filtering to `Foreground` restores the
 * pre-refactor foreground-only semantics.
 */
internal fun shouldTriggerForegroundWork(visibility: AppVisibility): Boolean =
    visibility == AppVisibility.Foreground

/** Foreground-only view of [AppForegroundMonitor.events], per [shouldTriggerForegroundWork]. */
internal fun Flow<AppVisibility>.foregroundEvents(): Flow<AppVisibility> =
    filter { shouldTriggerForegroundWork(it) }
