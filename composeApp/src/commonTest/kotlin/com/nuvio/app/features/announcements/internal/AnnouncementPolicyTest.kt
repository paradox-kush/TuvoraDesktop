package com.nuvio.app.features.announcements.internal

import com.nuvio.app.features.announcements.api.Announcement
import com.nuvio.app.features.announcements.api.AnnouncementKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnouncementPolicyTest {

    private fun item(id: String, title: String = "Title $id") = Announcement(
        id = id,
        title = title,
        body = "Body $id",
        ctaLabel = null,
        ctaUrl = null,
        kind = AnnouncementKind.Info,
        startsAt = "2026-09-27T00:00:00Z",
    )

    // --- shouldFetch ---

    @Test
    fun `never fetched means fetch`() {
        assertTrue(AnnouncementPolicy.shouldFetch(lastFetchedAtMs = null, nowMs = 1_000L))
    }

    @Test
    fun `a fetch inside the interval is skipped`() {
        val last = 10_000_000L
        assertFalse(AnnouncementPolicy.shouldFetch(last, last))
        assertFalse(AnnouncementPolicy.shouldFetch(last, last + AnnouncementPolicy.FETCH_INTERVAL_MS - 1))
    }

    @Test
    fun `a fetch at or past the interval runs`() {
        val last = 10_000_000L
        assertTrue(AnnouncementPolicy.shouldFetch(last, last + AnnouncementPolicy.FETCH_INTERVAL_MS))
        assertTrue(AnnouncementPolicy.shouldFetch(last, last + AnnouncementPolicy.FETCH_INTERVAL_MS * 3))
    }

    @Test
    fun `a clock that went backwards fetches instead of waiting forever`() {
        assertTrue(AnnouncementPolicy.shouldFetch(lastFetchedAtMs = 10_000_000L, nowMs = 9_999_999L))
    }

    @Test
    fun `the fetch interval is six hours`() {
        assertEquals(6L * 60 * 60 * 1000, AnnouncementPolicy.FETCH_INTERVAL_MS)
    }

    // --- pick ---

    @Test
    fun `pick returns the first item in server order`() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals("a", AnnouncementPolicy.pick(items, emptySet())?.id)
    }

    @Test
    fun `pick skips dismissed ids`() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals("c", AnnouncementPolicy.pick(items, setOf("a", "b"))?.id)
    }

    @Test
    fun `pick skips blank titles`() {
        val items = listOf(item("a", title = "   "), item("b", title = ""), item("c"))
        assertEquals("c", AnnouncementPolicy.pick(items, emptySet())?.id)
    }

    @Test
    fun `pick returns null when everything is dismissed or empty`() {
        assertNull(AnnouncementPolicy.pick(emptyList(), emptySet()))
        assertNull(AnnouncementPolicy.pick(listOf(item("a")), setOf("a")))
    }

    // --- safeCtaUrl / cta ---

    @Test
    fun `only https urls are allowed as a CTA`() {
        assertEquals("https://tuvora.co/news", AnnouncementPolicy.safeCtaUrl("https://tuvora.co/news"))
        assertNull(AnnouncementPolicy.safeCtaUrl("http://tuvora.co/news"))
        assertNull(AnnouncementPolicy.safeCtaUrl("javascript:alert(1)"))
        assertNull(AnnouncementPolicy.safeCtaUrl("intent://scan/#Intent;end"))
        assertNull(AnnouncementPolicy.safeCtaUrl("file:///etc/passwd"))
        assertNull(AnnouncementPolicy.safeCtaUrl("https://"))
        assertNull(AnnouncementPolicy.safeCtaUrl("https://tuvora.co/a b"))
        assertNull(AnnouncementPolicy.safeCtaUrl(""))
        assertNull(AnnouncementPolicy.safeCtaUrl(null))
    }

    @Test
    fun `the CTA is hidden when the label or the url is missing`() {
        assertNull(AnnouncementPolicy.cta(label = null, url = "https://tuvora.co"))
        assertNull(AnnouncementPolicy.cta(label = "  ", url = "https://tuvora.co"))
        assertNull(AnnouncementPolicy.cta(label = "Read more", url = null))
        assertNull(AnnouncementPolicy.cta(label = "Read more", url = "http://tuvora.co"))
        assertEquals(
            "Read more" to "https://tuvora.co",
            AnnouncementPolicy.cta(label = " Read more ", url = "https://tuvora.co"),
        )
    }
}
