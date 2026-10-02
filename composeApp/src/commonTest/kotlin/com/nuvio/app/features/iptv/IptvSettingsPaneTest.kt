package com.nuvio.app.features.iptv

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The desktop pane's state transitions and keyboard stepping, without Compose. */
class IptvSettingsPaneTest {
    private fun acc(id: String) = XtreamAccount(id = id, name = id, baseUrl = "http://h", username = "u", password = "p")
    private val a = acc("a")
    private val b = acc("b")
    private val c = acc("c")

    @AfterTest fun reset() = IptvSettingsPane.resetForTest()

    @Test
    fun `selection stays valid as playlists come and go`() {
        assertNull(IptvSettingsPane.resolveSelection(emptyList(), "a"))
        assertEquals("a", IptvSettingsPane.resolveSelection(listOf(a, b), null))
        assertEquals("b", IptvSettingsPane.resolveSelection(listOf(a, b), "b"))
        assertEquals("a", IptvSettingsPane.resolveSelection(listOf(a), "b"))
    }

    @Test
    fun `after a removal the next row is selected, else the previous, else none`() {
        assertEquals("c", IptvSettingsPane.selectionAfterRemoval(listOf(a, b, c), "b"))
        assertEquals("b", IptvSettingsPane.selectionAfterRemoval(listOf(a, b, c), "c"))
        assertEquals("b", IptvSettingsPane.selectionAfterRemoval(listOf(a, b), "a"))
        assertNull(IptvSettingsPane.selectionAfterRemoval(listOf(a), "a"))
    }

    @Test
    fun `arrow keys step through the list and stop at the ends`() {
        val keys = listOf("a", "b", "c")
        assertEquals("b", IptvSettingsPane.step(keys, "a", +1))
        assertEquals("c", IptvSettingsPane.step(keys, "c", +1), "no wrap past the end")
        assertEquals("a", IptvSettingsPane.step(keys, "a", -1), "no wrap past the start")
        assertEquals("a", IptvSettingsPane.step(keys, null, +1), "nothing selected: start at the top")
        assertNull(IptvSettingsPane.step(emptyList(), "a", +1))
    }

    @Test
    fun `selecting a row shows its details and clears popovers and rename`() {
        IptvSettingsPane.openAdd()
        IptvSettingsPane.askDestructive(DestructiveAction.REMOVE, "a", PopoverOrigin.Row)
        IptvSettingsPane.startRename("a")
        IptvSettingsPane.select("b")
        val s = IptvSettingsPane.state.value
        assertEquals(PaneMode.Detail, s.mode)
        assertEquals("b", s.selectedKey)
        assertNull(s.pending)
        assertNull(s.renamingKey)
    }

    @Test
    fun `opening the add pane starts on the code field, and a held code opens its preview`() {
        IptvSettingsPane.openAdd()
        assertEquals(AddStage.Entry, IptvSettingsPane.state.value.addStage)
        IptvSettingsPane.openPreview()
        assertEquals(PaneMode.Add, IptvSettingsPane.state.value.mode)
        assertEquals(AddStage.Preview, IptvSettingsPane.state.value.addStage)
        IptvSettingsPane.backToEntry()
        assertEquals(AddStage.Entry, IptvSettingsPane.state.value.addStage)
        IptvSettingsPane.closeAdd()
        assertEquals(PaneMode.Detail, IptvSettingsPane.state.value.mode)
    }

    @Test
    fun `one popover at a time and Esc dismisses it`() {
        IptvSettingsPane.askDestructive(DestructiveAction.DETACH, "a", PopoverOrigin.Detail)
        IptvSettingsPane.askDestructive(DestructiveAction.REMOVE, "b", PopoverOrigin.Row)
        assertEquals(PendingDestructive(DestructiveAction.REMOVE, "b", PopoverOrigin.Row), IptvSettingsPane.state.value.pending)
        IptvSettingsPane.dismissDestructive()
        assertNull(IptvSettingsPane.state.value.pending)
    }

    @Test
    fun `rename opens on the details of that playlist`() {
        IptvSettingsPane.openAdd()
        IptvSettingsPane.startRename("b")
        val s = IptvSettingsPane.state.value
        assertEquals("b", s.selectedKey)
        assertEquals("b", s.renamingKey)
        assertEquals(PaneMode.Detail, s.mode)
        IptvSettingsPane.stopRename()
        assertNull(IptvSettingsPane.state.value.renamingKey)
    }
}
