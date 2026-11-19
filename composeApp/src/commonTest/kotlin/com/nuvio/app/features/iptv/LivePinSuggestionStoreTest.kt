package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.PinSuggestionPolicy.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakePersistence : LivePinSuggestionPersistence {
    val saved = mutableMapOf<Int, String>()
    var saves = 0
    override fun load(profileId: Int): String? = saved[profileId]
    override fun save(profileId: Int, json: String) { saved[profileId] = json; saves++ }
}

class LivePinSuggestionStoreTest {
    private val day = PinSuggestionPolicy.DAY_MS
    private var now = 1_000 * day
    private var profile = 1
    private val disk = FakePersistence()
    private fun store() = LivePinSuggestionStore(disk, { now }, { profile })

    @Test
    fun `record counts plays and keeps the newest name and logo`() {
        val s = store()
        s.recordPlay("x:1", "Alpha", null)
        now += 1000
        s.recordPlay("x:1", "Alpha HD", "a.png")
        val c = s.state.value.channels.single()
        assertEquals(listOf(1_000 * day, 1_000 * day + 1000), c.playTimesMs, "two plays in order")
        assertEquals("Alpha HD", c.name, "name")
        assertEquals("a.png", c.logo, "logo")
    }

    @Test
    fun `a generic title keeps the real name`() {
        val s = store()
        s.recordPlay("x:1", "Alpha", "a.png")
        s.recordPlay("x:1", com.nuvio.app.core.contracts.GENERIC_LIVE_CHANNEL_NAME, null)
        assertEquals("Alpha", s.state.value.channels.single().name, "placeholder never replaces a real name")
        assertEquals("a.png", s.state.value.channels.single().logo, "logo kept")
    }

    @Test
    fun `plays older than 14 days are forgotten when a new play is recorded`() {
        val s = store()
        s.recordPlay("x:old", "Old", null)
        s.recordPlay("x:1", "One", null)
        now += 15 * day
        s.recordPlay("x:1", "One", null)
        assertEquals(listOf("x:1"), s.state.value.channels.map { it.contentId }, "x:old has no plays left in the window")
        assertEquals(listOf(now), s.state.value.channels.single().playTimesMs, "only the new play remains")
    }

    @Test
    fun `channels are capped keeping the most recently played`() {
        val s = store()
        repeat(LivePlayCounts.MAX_CHANNELS + 5) { i ->
            now += 1000
            s.recordPlay("x:$i", "Ch $i", null)
        }
        val ids = s.state.value.channels.map { it.contentId }
        assertEquals(LivePlayCounts.MAX_CHANNELS, ids.size, "channel cap")
        assertTrue("x:${LivePlayCounts.MAX_CHANNELS + 4}" in ids, "newest kept")
        assertFalse("x:0" in ids, "oldest dropped")
    }

    @Test
    fun `play times per channel are capped`() {
        val s = store()
        repeat(LivePlayCounts.MAX_PLAYS_PER_CHANNEL + 7) { now += 1000; s.recordPlay("x:1", "One", null) }
        val times = s.state.value.channels.single().playTimesMs
        assertEquals(LivePlayCounts.MAX_PLAYS_PER_CHANNEL, times.size, "per-channel cap")
        assertEquals(now, times.last(), "newest kept")
    }

    @Test
    fun `clear play counts forgets counts but keeps the switch and snooze`() {
        val s = store()
        s.recordPlay("x:1", "One", null)
        s.snooze()
        s.clearPlayCounts()
        assertTrue(s.state.value.channels.isEmpty(), "counts gone")
        assertTrue(s.state.value.snoozedUntilMs > now, "snooze kept")
        s.setEnabled(false)
        s.clearPlayCounts()
        assertFalse(s.state.value.enabled, "switch kept")
        val reloaded = store().also { it.ensureLoaded() }
        assertTrue(reloaded.state.value.channels.isEmpty(), "cleared counts are cleared on disk too")
    }

    @Test
    fun `switched off stops counting and switching on resumes`() {
        val s = store()
        s.setEnabled(false)
        s.recordPlay("x:1", "One", null)
        assertTrue(s.state.value.channels.isEmpty(), "nothing recorded while off")
        s.setEnabled(true)
        s.recordPlay("x:1", "One", null)
        assertEquals(1, s.state.value.channels.size, "recording resumes")
    }

    @Test
    fun `state survives a restart and belongs to one profile`() {
        val s = store()
        s.recordPlay("x:1", "One", null)
        s.snooze()
        val again = store().also { it.ensureLoaded() }
        assertEquals(s.state.value, again.state.value, "round trip")
        profile = 2
        again.onProfileChanged()
        assertEquals(LivePinSuggestionState(), again.state.value, "another profile starts empty")
        again.recordPlay("x:2", "Two", null)
        profile = 1
        again.onProfileChanged()
        assertEquals(listOf("x:1"), again.state.value.channels.map { it.contentId }, "profile 1 kept its own counts")
    }

    @Test
    fun `corrupt stored json starts empty instead of crashing`() {
        disk.saved[1] = "{not json"
        val s = store()
        s.ensureLoaded()
        assertEquals(LivePinSuggestionState(), s.state.value, "defaults")
    }

    @Test
    fun `playlist removal drops its channels and an edit re-keys them`() {
        val s = store()
        s.recordPlay("xtream:a:1", "A1", null)
        s.recordPlay("xtream:b:1", "B1", null)
        s.migrateIdPrefix("xtream:a:", "xtream:c:")
        assertEquals(setOf("xtream:c:1", "xtream:b:1"), s.state.value.channels.map { it.contentId }.toSet(), "re-keyed")
        s.migrateIdPrefix("xtream:b:", null)
        assertEquals(listOf("xtream:c:1"), s.state.value.channels.map { it.contentId }, "removed")
    }

    @Test
    fun `clear local state forgets memory and reloads from disk on next use`() {
        val s = store()
        s.recordPlay("x:1", "One", null)
        s.clearLocalState()
        assertEquals(LivePinSuggestionState(), s.state.value, "memory emptied")
    }

    // --- Pin / Pin all with a fake port ---

    private class FakePort : LiveFavouritePort {
        val added = mutableListOf<Triple<String, String, String?>>()
        override fun addFavourite(contentId: String, name: String, logo: String?) { added += Triple(contentId, name, logo) }
    }

    private val four = listOf(
        Candidate("c1", "One", "1.png", 9),
        Candidate("c2", "Two", null, 7),
        Candidate("c3", "Three", null, 5),
        Candidate("c4", "Four", null, 3),
    )

    @Test
    fun `pin adds one favourite with its name and logo`() {
        val port = FakePort()
        LivePinSuggestionActions(store(), port).pin(four[0])
        assertEquals(listOf(Triple<String, String, String?>("c1", "One", "1.png")), port.added, "one favourite")
    }

    @Test
    fun `pin all pins every offered channel least played first so the most played ends on top`() {
        val port = FakePort()
        val n = LivePinSuggestionActions(store(), port).pinAll(four)
        assertEquals(4, n, "count")
        assertEquals(listOf("c4", "c3", "c2", "c1"), port.added.map { it.first }, "the most played is pinned last, newest, on top")
    }

    @Test
    fun `pin all with nothing offered pins nothing`() {
        val port = FakePort()
        assertEquals(0, LivePinSuggestionActions(store(), port).pinAll(emptyList()), "count")
        assertTrue(port.added.isEmpty(), "no favourites")
    }

    @Test
    fun `not now snoozes for 14 days and stop suggesting turns it off`() {
        val s = store()
        val actions = LivePinSuggestionActions(s, FakePort())
        actions.notNow()
        assertEquals(now + 14 * day, s.state.value.snoozedUntilMs, "snoozed 14 days")
        assertTrue(s.state.value.enabled, "still on")
        actions.stopSuggesting()
        assertFalse(s.state.value.enabled, "off")
    }
}
