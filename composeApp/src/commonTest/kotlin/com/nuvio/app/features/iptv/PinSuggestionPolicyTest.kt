package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.PinSuggestionPolicy.ChannelPlays
import com.nuvio.app.features.iptv.PinSuggestionPolicy.Prefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinSuggestionPolicyTest {
    private val now = 100 * PinSuggestionPolicy.DAY_MS
    private val day = PinSuggestionPolicy.DAY_MS

    private fun ch(id: String, vararg agesDays: Int) =
        ChannelPlays(id, "Channel $id", null, agesDays.map { now - it * day })

    private fun ids(
        channels: List<ChannelPlays>,
        pinned: Set<String> = emptySet(),
        prefs: Prefs = Prefs(),
        at: Long = now,
    ) = PinSuggestionPolicy.suggest(channels, pinned, prefs, at).map { it.contentId }

    @Test
    fun `constants are the agreed rules`() {
        assertEquals(3, PinSuggestionPolicy.MIN_PLAYS, "min plays")
        assertEquals(4, PinSuggestionPolicy.MAX_SUGGESTIONS, "max suggestions")
        assertEquals(2, PinSuggestionPolicy.MIN_QUALIFYING, "min qualifying")
        assertEquals(14 * day, PinSuggestionPolicy.WINDOW_MS, "window")
        assertEquals(14 * day, PinSuggestionPolicy.SNOOZE_MS, "snooze")
    }

    @Test
    fun `nothing when no channel has been played`() {
        assertTrue(ids(emptyList()).isEmpty(), "empty history")
    }

    @Test
    fun `three plays qualify and two do not`() {
        assertEquals(listOf("b", "a"), ids(listOf(ch("a", 1, 2, 3), ch("b", 0, 0, 0), ch("c", 1, 2))), "a and b qualify (b played more recently), c has two plays")
    }

    @Test
    fun `a play exactly 14 days old counts and one just older does not`() {
        val edge = ChannelPlays("edge", "Edge", null, listOf(now - 14 * day, now - 5 * day, now))
        val stale = ChannelPlays("stale", "Stale", null, listOf(now - 14 * day - 1, now - 5 * day, now))
        val other = ch("other", 1, 2, 3)
        assertEquals(listOf("edge", "other").sorted(), ids(listOf(edge, stale, other)).sorted(), "boundary instant counts, one ms older does not")
    }

    @Test
    fun `plays outside the window never qualify`() {
        assertTrue(ids(listOf(ch("a", 15, 16, 17), ch("b", 20, 21, 22))).isEmpty(), "all plays older than 14 days")
    }

    @Test
    fun `a single qualifying channel offers nothing`() {
        assertTrue(ids(listOf(ch("a", 1, 2, 3), ch("b", 1))).isEmpty(), "one qualifier is below the minimum of two")
    }

    @Test
    fun `two qualifying channels are offered`() {
        assertEquals(2, ids(listOf(ch("a", 1, 2, 3), ch("b", 1, 2, 3))).size, "exactly the minimum")
    }

    @Test
    fun `pinned or favourited channels are excluded before the minimum is applied`() {
        val all = listOf(ch("a", 1, 2, 3), ch("b", 1, 2, 3), ch("c", 1, 2, 3))
        assertEquals(listOf("b", "c"), ids(all, pinned = setOf("a")), "a is excluded")
        assertTrue(ids(all, pinned = setOf("a", "b")).isEmpty(), "only c left, below the minimum")
    }

    @Test
    fun `at most four are offered, most played first`() {
        val all = listOf(
            ch("p3", 1, 2, 3),
            ch("p7", 1, 2, 3, 4, 5, 6, 7),
            ch("p5", 1, 2, 3, 4, 5),
            ch("p4", 1, 2, 3, 4),
            ch("p6", 1, 2, 3, 4, 5, 6),
        )
        assertEquals(listOf("p7", "p6", "p5", "p4"), ids(all), "top four by plays, p3 left out")
    }

    @Test
    fun `ties go to the most recently played then to the id`() {
        val all = listOf(ch("b", 3, 4, 5), ch("a", 3, 4, 5), ch("c", 1, 4, 5))
        assertEquals(listOf("c", "a", "b"), ids(all), "c played most recently; a before b by id")
    }

    @Test
    fun `candidates carry name logo and the qualifying play count`() {
        val c = ChannelPlays("a", "Alpha", "logo.png", listOf(now, now - day, now - 2 * day, now - 30 * day))
        val out = PinSuggestionPolicy.suggest(listOf(c, ch("b", 1, 2, 3)), emptySet(), Prefs(), now)
        val alpha = out.first { it.contentId == "a" }
        assertEquals("Alpha", alpha.name, "name")
        assertEquals("logo.png", alpha.logo, "logo")
        assertEquals(3, alpha.plays, "only plays inside the window count")
    }

    @Test
    fun `switched off means never`() {
        val all = listOf(ch("a", 1, 2, 3), ch("b", 1, 2, 3))
        assertTrue(ids(all, prefs = Prefs(enabled = false)).isEmpty(), "off")
        assertTrue(ids(all, prefs = Prefs(enabled = false), at = now + 365 * day).isEmpty(), "off stays off a year later")
    }

    @Test
    fun `a snooze hides the rail until it ends`() {
        val all = listOf(ch("a", 1, 2, 3), ch("b", 1, 2, 3))
        val until = PinSuggestionPolicy.snoozeUntil(now)
        assertEquals(now + 14 * day, until, "snooze is 14 days")
        val snoozed = Prefs(snoozedUntilMs = until)
        assertTrue(ids(all, prefs = snoozed).isEmpty(), "snoozed now")
        assertTrue(ids(all, prefs = snoozed, at = until - 1).isEmpty(), "still snoozed one ms before the end")
        val fresh = listOf("a", "b").map { ChannelPlays(it, it, null, listOf(until - day, until - 2 * day, until - 3 * day)) }
        assertEquals(listOf("a", "b"), ids(fresh, prefs = snoozed, at = until), "offered again when the snooze ends")
    }
}
