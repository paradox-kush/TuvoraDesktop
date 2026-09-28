package com.nuvio.app.features.announcements.internal

import com.nuvio.app.features.announcements.api.Announcement

/**
 * Pure decisions for in-app announcements — no I/O, so they test without the network or the UI.
 *
 * Refresh is deliberately slow (6 h) and visit-driven: the home screen asks on each RESUMED, and
 * this says whether that visit may hit the network at all (house rule: recurring network work is
 * delta-shaped, lifecycle-bound and cheap).
 */
internal object AnnouncementPolicy {
    const val FETCH_INTERVAL_MS: Long = 6L * 60 * 60 * 1000

    /** Fetch when never fetched, when the interval has elapsed, or when the clock went backwards. */
    fun shouldFetch(lastFetchedAtMs: Long?, nowMs: Long): Boolean {
        if (lastFetchedAtMs == null) return true
        if (nowMs < lastFetchedAtMs) return true
        return nowMs - lastFetchedAtMs >= FETCH_INTERVAL_MS
    }

    /** First item in server order that is not dismissed and has a title. */
    fun pick(items: List<Announcement>, dismissedIds: Set<String>): Announcement? =
        items.firstOrNull { it.id !in dismissedIds && it.title.isNotBlank() }

    /** Only well-formed https links; anything else (http, javascript:, intent:, file:) is dropped. */
    fun safeCtaUrl(url: String?): String? {
        val candidate = url?.trim() ?: return null
        if (!candidate.startsWith(HTTPS, ignoreCase = true)) return null
        if (candidate.length <= HTTPS.length) return null
        if (candidate.any { it.isWhitespace() }) return null
        return candidate
    }

    /** The CTA to show as (label, url), or null when either half is missing or unsafe. */
    fun cta(label: String?, url: String?): Pair<String, String>? {
        val cleanLabel = label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanUrl = safeCtaUrl(url) ?: return null
        return cleanLabel to cleanUrl
    }

    private const val HTTPS = "https://"
}
