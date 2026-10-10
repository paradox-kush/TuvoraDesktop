package com.nuvio.app.features.addons

/**
 * What is fetched again after an add-on load fails — a manifest, or one of its catalog rows on Home — and when.
 *
 * Home's first load fetched every add-on manifest and every catalog row exactly once. One bad moment
 * (a cold network, a slow add-on timing out) left the failed add-ons without a manifest and the failed
 * rows absent, with no error while Continue Watching had items, until the app was restarted.
 *
 * Retries are delta-shaped and bounded: only what failed is fetched again, a fixed short ladder right
 * after the failure, then one pass each time Home becomes visible again (never a timer).
 */
internal object AddonLoadRetryPolicy {
    /** Waits before each automatic retry that follows a failure; after the last one Home waits for a visit. */
    val BACKOFF_MS = listOf(2_000L, 8_000L)

    fun delayBeforeRetry(retriesDone: Int): Long? = BACKOFF_MS.getOrNull(retriesDone)

    /** Rows to fetch again: still on Home and failed last time. Rows that loaded are never re-fetched. */
    fun catalogsToRetry(requestedCacheKeys: List<String>, failedCacheKeys: Set<String>): List<String> =
        requestedCacheKeys.filter { it in failedCacheKeys }.distinct()

    /** Add-ons to fetch again: enabled, no manifest, last fetch failed, none already in flight. */
    fun manifestsToRetry(addons: List<ManagedAddon>): List<String> =
        addons
            .filter { it.enabled && it.manifest == null && !it.isRefreshing && !it.errorMessage.isNullOrBlank() }
            .map { it.manifestUrl }
            .distinct()

    /** Whether Home says some rows are missing (with Retry) instead of silently showing fewer. */
    fun showsPartialFailure(failedRowCount: Int, failedManifestCount: Int, isLoading: Boolean): Boolean =
        !isLoading && (failedRowCount > 0 || failedManifestCount > 0)
}
