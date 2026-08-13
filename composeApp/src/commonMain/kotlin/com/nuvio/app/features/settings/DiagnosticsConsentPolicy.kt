package com.nuvio.app.features.settings

/**
 * Whether this device reports diagnostics, given what the user has previously chosen.
 *
 * This drives PostHog's opt-out, so it governs every event the app sends — crashes, ANRs, screens,
 * playback, abnormal exits — not only the crash reports the setting is named after.
 *
 * The default is on, and deliberately so: shipped off, it left the app blind. Two weeks after the
 * gate went out, 416 of the 427 users still reporting were on builds that predated it, and the rest
 * had gone silent simply by updating. Diagnostics that are off by default are diagnostics that do
 * not exist, and the bugs they would have caught get paid for by users instead.
 *
 * A stored value always wins. Someone who has turned this off has made a decision, and changing the
 * default must never quietly reverse it.
 */
internal fun resolveDiagnosticsEnabled(stored: Boolean?): Boolean = stored ?: DIAGNOSTICS_DEFAULT_ENABLED

internal const val DIAGNOSTICS_DEFAULT_ENABLED = true
