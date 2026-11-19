package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeSectionOrderPolicyTest {
    private val todaysOrder = listOf("hero", "new_episodes", "sports", "continue_watching", "live_recents", "upcoming", "catalogs")
    private val returningOrder = listOf("continue_watching", "live_recents", "hero", "new_episodes", "sports", "upcoming", "catalogs")

    private fun config(customized: Boolean = true, vararg entries: Pair<String, Boolean>) =
        HomeSectionsConfig(customized, entries.map { HomeSectionEntry(it.first, it.second) })

    private fun ids(entries: List<HomeSectionEntry>) = entries.map { it.id }

    // ---- default ----

    @Test
    fun `no config and no history keeps today's order`() {
        assertEquals(todaysOrder, HomeSectionOrderPolicy.visibleOrder(null, hasWatchHistory = false), "default order")
    }

    @Test
    fun `no config with history puts Continue Watching and Live TV first`() {
        assertEquals(returningOrder, HomeSectionOrderPolicy.visibleOrder(null, hasWatchHistory = true), "returning-user order")
    }

    @Test
    fun `customized false behaves like no config, ignoring stored sections`() {
        val stale = config(false, "catalogs" to true, "hero" to false)

        assertEquals(todaysOrder, HomeSectionOrderPolicy.visibleOrder(stale, hasWatchHistory = false), "no history")
        assertEquals(returningOrder, HomeSectionOrderPolicy.visibleOrder(stale, hasWatchHistory = true), "history")
    }

    @Test
    fun `history does not reorder a customised layout`() {
        val mine = config(true, "catalogs" to true, "hero" to true, "continue_watching" to true)

        val withHistory = HomeSectionOrderPolicy.visibleOrder(mine, hasWatchHistory = true)
        val without = HomeSectionOrderPolicy.visibleOrder(mine, hasWatchHistory = false)

        assertEquals(without, withHistory, "customised order is the same with or without history")
        assertEquals(listOf("catalogs", "hero", "continue_watching"), withHistory.take(3), "stored order leads")
    }

    // ---- customised, hidden ----

    @Test
    fun `customised order is used as stored`() {
        val mine = config(true, "sports" to true, "catalogs" to true, "hero" to true)

        assertEquals(listOf("sports", "catalogs", "hero"), HomeSectionOrderPolicy.visibleOrder(mine, false).take(3), "stored order")
    }

    @Test
    fun `hidden sections are not rendered but stay listed`() {
        val mine = config(true, "hero" to false, "sports" to true, "catalogs" to true)

        assertFalse("hero" in HomeSectionOrderPolicy.visibleOrder(mine, false), "hidden hero not rendered")
        assertEquals(false, HomeSectionOrderPolicy.listed(mine, false).first { it.id == "hero" }.enabled, "hidden hero still in settings list")
    }

    @Test
    fun `supported ids missing from a stored layout are appended in default order, enabled`() {
        val mine = config(true, "catalogs" to true, "hero" to true)

        val resolved = HomeSectionOrderPolicy.resolve(mine, false)

        assertEquals(listOf("catalogs", "hero", "new_episodes", "sports", "continue_watching", "live_recents", "upcoming"), ids(resolved), "missing appended")
        assertTrue(resolved.drop(2).all { it.enabled }, "appended sections are enabled")
    }

    @Test
    fun `duplicate ids keep the first entry`() {
        val mine = config(true, "hero" to false, "hero" to true)

        assertEquals(false, HomeSectionOrderPolicy.resolve(mine, false).first { it.id == "hero" }.enabled, "first wins")
        assertEquals(1, HomeSectionOrderPolicy.resolve(mine, false).count { it.id == "hero" }, "no duplicates")
    }

    // ---- unknown / unsupported ----

    @Test
    fun `unknown id is skipped on render but preserved in position on write`() {
        val mine = config(true, "hero" to true, "from_the_future" to true, "sports" to true, "catalogs" to true)

        assertFalse("from_the_future" in HomeSectionOrderPolicy.visibleOrder(mine, false), "not rendered")
        assertFalse(HomeSectionOrderPolicy.listed(mine, false).any { it.id == "from_the_future" }, "not listed")

        val moved = HomeSectionOrderPolicy.move(mine, false, fromId = "catalogs", toId = "hero")

        val written = ids(moved.sections)
        assertEquals(listOf("catalogs", "hero", "from_the_future", "sports"), written.take(4), "unknown id keeps its place after the move")
        val toggled = HomeSectionOrderPolicy.setEnabled(mine, false, "sports", false)
        assertEquals(ids(mine.sections), ids(toggled.sections).take(4), "toggle keeps order and unknown id")
    }

    @Test
    fun `an id the platform does not support is skipped silently`() {
        val supported = setOf("hero", "continue_watching", "catalogs")
        val mine = config(true, "sports" to true, "hero" to true, "continue_watching" to true, "catalogs" to true)

        assertEquals(listOf("hero", "continue_watching", "catalogs"), HomeSectionOrderPolicy.visibleOrder(mine, false, supported), "unsupported skipped")
        assertEquals(listOf("hero", "continue_watching", "catalogs"), ids(HomeSectionOrderPolicy.listed(mine, false, supported)), "unsupported not listed")
        assertEquals("sports", HomeSectionOrderPolicy.setEnabled(mine, false, "hero", false).sections.first().id, "unsupported id still written back")
    }

    // ---- user changes ----

    @Test
    fun `moving a section writes the full list customized`() {
        val moved = HomeSectionOrderPolicy.move(null, hasWatchHistory = false, fromId = "catalogs", toId = "hero")

        assertTrue(moved.customized, "a user move marks the layout customized")
        assertEquals(listOf("catalogs") + todaysOrder.filter { it != "catalogs" }, ids(moved.sections), "full list, catalogs first")
    }

    @Test
    fun `a move from an untouched returning user freezes the returning-user order`() {
        val moved = HomeSectionOrderPolicy.move(null, hasWatchHistory = true, fromId = "upcoming", toId = "hero")

        assertEquals(listOf("continue_watching", "live_recents", "upcoming", "hero"), ids(moved.sections).take(4), "starts from the history default")
    }

    @Test
    fun `a no-op move leaves an untouched layout untouched`() {
        assertFalse(HomeSectionOrderPolicy.move(null, false, "hero", "hero").customized, "same slot is not a change")
        assertFalse(HomeSectionOrderPolicy.move(null, false, "nope", "hero").customized, "unknown id is not a change")
    }

    @Test
    fun `hero can move below other sections`() {
        val moved = HomeSectionOrderPolicy.move(null, false, fromId = "hero", toId = "catalogs")

        assertEquals("hero", HomeSectionOrderPolicy.visibleOrder(moved, false).last(), "hero is last")
    }

    @Test
    fun `hiding a section writes customized with that flag off`() {
        val hidden = HomeSectionOrderPolicy.setEnabled(null, false, "sports", false)

        assertTrue(hidden.customized, "customized")
        assertEquals(false, hidden.sections.first { it.id == "sports" }.enabled, "sports off")
        assertEquals(6, HomeSectionOrderPolicy.visibleOrder(hidden, false).size, "the other six render")
    }

    // ---- reset ----

    @Test
    fun `Reset to Default is written customized false and restores the device default`() {
        val reset = HomeSectionOrderPolicy.reset()

        assertFalse(reset.customized, "explicit customized false so other devices follow")
        assertEquals(todaysOrder, HomeSectionOrderPolicy.visibleOrder(reset, false), "default without history")
        assertEquals(returningOrder, HomeSectionOrderPolicy.visibleOrder(reset, true), "default with history")
    }

    // ---- toggles in step ----

    @Test
    fun `the three switches override the listed flags`() {
        val listed = HomeSectionOrderPolicy.listed(null, false)

        val shown = HomeSectionOrderPolicy.withToggles(listed, heroEnabled = false, continueWatchingVisible = false, showLiveOnHome = false)

        fun enabled(id: String) = shown.first { it.id == id }.enabled
        assertFalse(enabled("hero"), "hero switch")
        assertFalse(enabled("continue_watching"), "continue watching switch")
        assertFalse(enabled("live_recents"), "live tv switch")
        assertTrue(enabled("sports"), "sections without a switch are untouched")
    }

    @Test
    fun `a remote layout's flags drive the switches`() {
        val remote = config(true, "hero" to false, "continue_watching" to true, "live_recents" to false)

        assertEquals(false, HomeSectionOrderPolicy.toggleFor(remote, "hero"), "hero off")
        assertEquals(true, HomeSectionOrderPolicy.toggleFor(remote, "continue_watching"), "cw on")
        assertEquals(false, HomeSectionOrderPolicy.toggleFor(remote, "live_recents"), "live off")
        assertNull(HomeSectionOrderPolicy.toggleFor(remote, "sports"), "not in layout")
        assertNull(HomeSectionOrderPolicy.toggleFor(config(false, "hero" to false), "hero"), "default layouts do not drive switches")
        assertNull(HomeSectionOrderPolicy.toggleFor(null, "hero"), "no layout")
    }

    @Test
    fun `a switch that already matches does not customise the layout`() {
        assertFalse(HomeSectionOrderPolicy.wouldChange(null, false, "hero", enabled = true), "default is on")
        assertTrue(HomeSectionOrderPolicy.wouldChange(null, false, "hero", enabled = false), "turning off is a change")
        val off = config(true, "hero" to false)
        assertFalse(HomeSectionOrderPolicy.wouldChange(off, false, "hero", enabled = false), "already off")
        assertTrue(HomeSectionOrderPolicy.wouldChange(off, false, "hero", enabled = true), "turning back on")
    }

    @Test
    fun `flipping a switch and the list flag stay in step both ways`() {
        val offViaSwitch = HomeSectionOrderPolicy.setEnabled(null, false, "live_recents", false)
        assertEquals(false, HomeSectionOrderPolicy.toggleFor(offViaSwitch, "live_recents"), "switch off -> flag off")

        val onViaList = HomeSectionOrderPolicy.setEnabled(offViaSwitch, false, "live_recents", true)
        assertEquals(true, HomeSectionOrderPolicy.toggleFor(onViaList, "live_recents"), "flag on -> switch on")
        assertTrue(onViaList.customized, "both are user changes")
    }
}
