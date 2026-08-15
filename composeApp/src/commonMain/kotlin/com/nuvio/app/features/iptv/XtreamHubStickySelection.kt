package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fix 1 (sticky provider): the hub's last on-screen selection — provider id + section tab name —
 * persisted per profile via [XtreamAccountStorage.saveHubSelectionJson] and restored on the next
 * fresh entry (cold start, or any resetForProfile).
 *
 * Deliberately DEVICE-LOCAL UI state: it lives in the local prefs bag, NOT on the [XtreamAccount],
 * precisely so it never rides the account sync — which provider you were browsing on this device
 * is not a fact about the playlist. Category and scroll state stay session-only on purpose.
 */
@Serializable
internal data class XtreamHubSelection(
    val accountId: String? = null,
    val section: String? = null,
)

private val hubSelectionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Corrupted or absent storage reads as "nothing remembered", never a throw. */
internal fun parseHubSelection(json: String?): XtreamHubSelection? {
    if (json.isNullOrBlank()) return null
    return try {
        hubSelectionJson.decodeFromString<XtreamHubSelection>(json)
    } catch (e: Exception) {
        null
    }
}

internal fun encodeHubSelection(selection: XtreamHubSelection): String =
    hubSelectionJson.encodeToString(XtreamHubSelection.serializer(), selection)

/**
 * Which account the hub lands on. The in-session choice wins; a fresh entry (current == null)
 * restores the remembered provider — but only while that account still exists AND is enabled.
 * Otherwise the first ENABLED account (the pre-fix behavior): a remembered id whose playlist was
 * deleted or toggled off must fall back, never resurrect. Content-type clamping (a section the
 * account has disabled) stays the caller's job — this only decides WHICH account.
 */
internal fun resolveStickyAccount(
    current: String?,
    remembered: String?,
    accounts: List<XtreamAccount>,
): String? {
    val enabled = accounts.filter { it.enabled }
    return (current ?: remembered)?.takeIf { id -> enabled.any { it.id == id } }
        ?: enabled.firstOrNull()?.id
}

/**
 * The remembered section tab (stored by name), tolerating junk or names written by other builds —
 * anything unrecognized reads as "nothing remembered" and yields [fallback], never a throw.
 */
internal fun resolveStickySection(remembered: String?, fallback: XtreamHubSection): XtreamHubSection =
    XtreamHubSection.entries.firstOrNull { it.name == remembered } ?: fallback
