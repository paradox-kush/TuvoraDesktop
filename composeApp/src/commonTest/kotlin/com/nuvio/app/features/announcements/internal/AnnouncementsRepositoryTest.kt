package com.nuvio.app.features.announcements.internal

import com.nuvio.app.features.announcements.api.Announcement
import com.nuvio.app.features.announcements.api.AnnouncementKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnouncementsRepositoryTest {

    private class FakeStore : AnnouncementStore {
        val values = mutableMapOf<String, String>()
        override fun load(key: String): String? = values[key]
        override fun save(key: String, value: String) {
            values[key] = value
        }
    }

    private fun item(id: String) = Announcement(
        id = id,
        title = "Title $id",
        body = "Body $id",
        ctaLabel = "Open",
        ctaUrl = "https://tuvora.co/$id",
        kind = AnnouncementKind.Update,
        startsAt = "2026-09-27T00:00:00Z",
    )

    private val sixHours = AnnouncementPolicy.FETCH_INTERVAL_MS

    @Test
    fun `first refresh fetches and shows the first item`() = runTest {
        var calls = 0
        val repo = AnnouncementsRepository(
            fetch = { calls++; listOf(item("a"), item("b")) },
            store = FakeStore(),
            nowMs = { 1_000L },
        )
        repo.refreshIfDue()
        assertEquals(1, calls)
        assertEquals("a", repo.visible.value?.id)
    }

    @Test
    fun `a second refresh inside the interval makes no request`() = runTest {
        var calls = 0
        var now = 1_000L
        val repo = AnnouncementsRepository(
            fetch = { calls++; listOf(item("a")) },
            store = FakeStore(),
            nowMs = { now },
        )
        repo.refreshIfDue()
        now += sixHours - 1
        repo.refreshIfDue()
        assertEquals(1, calls)
        now += 1
        repo.refreshIfDue()
        assertEquals(2, calls)
    }

    @Test
    fun `a failed fetch keeps the cached list and does not stamp the fetch time`() = runTest {
        val store = FakeStore()
        var now = 1_000L
        var fail = false
        var calls = 0
        val repo = AnnouncementsRepository(
            fetch = { calls++; if (fail) error("offline") else listOf(item("a")) },
            store = store,
            nowMs = { now },
        )
        repo.refreshIfDue()
        fail = true
        now += sixHours
        repo.refreshIfDue()
        assertEquals("a", repo.visible.value?.id)
        // Not stamped: the next visit retries rather than waiting another six hours.
        repo.refreshIfDue()
        assertEquals(3, calls)
    }

    @Test
    fun `cache and last fetch time survive a restart`() = runTest {
        val store = FakeStore()
        var calls = 0
        AnnouncementsRepository(fetch = { calls++; listOf(item("a")) }, store = store, nowMs = { 1_000L })
            .refreshIfDue()
        val restarted = AnnouncementsRepository(
            fetch = { calls++; listOf(item("z")) },
            store = store,
            nowMs = { 2_000L },
        )
        restarted.refreshIfDue()
        assertEquals(1, calls)
        assertEquals("a", restarted.visible.value?.id)
    }

    @Test
    fun `dismiss hides the card immediately and persists across restarts`() = runTest {
        val store = FakeStore()
        val repo = AnnouncementsRepository(
            fetch = { listOf(item("a"), item("b")) },
            store = store,
            nowMs = { 1_000L },
        )
        repo.refreshIfDue()
        repo.dismiss("a")
        assertEquals("b", repo.visible.value?.id)
        repo.dismiss("b")
        assertNull(repo.visible.value)

        val restarted = AnnouncementsRepository(fetch = { listOf(item("a"), item("b")) }, store = store, nowMs = { 1_000L })
        restarted.refreshIfDue()
        assertNull(restarted.visible.value)
    }

    @Test
    fun `dismissed ids that the server no longer returns are pruned`() = runTest {
        val store = FakeStore()
        var now = 1_000L
        var serverItems = listOf(item("a"), item("b"))
        val repo = AnnouncementsRepository(fetch = { serverItems }, store = store, nowMs = { now })
        repo.refreshIfDue()
        repo.dismiss("a")
        serverItems = listOf(item("b"))
        now += sixHours
        repo.refreshIfDue()
        assertTrue("a" !in AnnouncementCodec.decodeIds(store.load(AnnouncementsRepository.KEY_DISMISSED)))
    }

    @Test
    fun `corrupt cached data is ignored instead of crashing`() = runTest {
        val store = FakeStore().apply {
            save(AnnouncementsRepository.KEY_ITEMS, "{not json")
            save(AnnouncementsRepository.KEY_DISMISSED, "][")
            save(AnnouncementsRepository.KEY_LAST_FETCHED_AT, "yesterday")
        }
        var calls = 0
        val repo = AnnouncementsRepository(fetch = { calls++; listOf(item("a")) }, store = store, nowMs = { 1_000L })
        repo.refreshIfDue()
        assertEquals(1, calls)
        assertEquals("a", repo.visible.value?.id)
    }

    @Test
    fun `wire rows map to announcements with unsafe CTAs dropped`() {
        val json = """
            [
              {"id":"1","title":"Hello","body":"World","cta_label":"Go","cta_url":"https://tuvora.co","kind":"update","starts_at":"2026-09-27T00:00:00Z"},
              {"id":"2","title":"Policy","body":"Changed","cta_label":"Go","cta_url":"http://evil.example","kind":"policy","starts_at":"2026-09-27T00:00:00Z"},
              {"id":"3","title":"New","body":"Kind","cta_label":null,"cta_url":null,"kind":"banner","starts_at":"2026-09-27T00:00:00Z","extra":1}
            ]
        """.trimIndent()
        val items = AnnouncementCodec.decodeWire(json)
        assertEquals(listOf("1", "2", "3"), items.map { it.id })
        assertEquals(AnnouncementKind.Update, items[0].kind)
        assertEquals("Go", items[0].ctaLabel)
        assertEquals("https://tuvora.co", items[0].ctaUrl)
        assertEquals(AnnouncementKind.Policy, items[1].kind)
        assertNull(items[1].ctaLabel)
        assertNull(items[1].ctaUrl)
        assertEquals(AnnouncementKind.Info, items[2].kind)
    }
}
