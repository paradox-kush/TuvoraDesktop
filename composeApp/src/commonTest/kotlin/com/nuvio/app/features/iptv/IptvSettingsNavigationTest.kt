package com.nuvio.app.features.iptv

import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.features.settings.SettingsPage.Iptv
import com.nuvio.app.features.settings.SettingsPage.IptvAddPlaylist
import com.nuvio.app.features.settings.SettingsPage.IptvCategoryChecklist
import com.nuvio.app.features.settings.SettingsPage.IptvContent
import com.nuvio.app.features.settings.SettingsPage.Root
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B103 — after a successful Add Playlist the iPhone pushed the playlist list on top of the submitted
 * form (list → form → list), so Back from the list returned to the form. Leaving an IPTV page must pop
 * exactly one entry off the navigator's stack, never push its parent.
 */
class IptvSettingsNavigationTest {

    /** A real push/pop stack, like the iPhone's native settings navigation. */
    private class FakeNavStack(vararg pages: SettingsPage) {
        val stack = pages.toMutableList()
        val navigation = IptvSettingsNavigation(
            open = { stack.add(it) },
            back = { stack.removeAt(stack.lastIndex) },
        )
    }

    @Test
    fun `saving a new playlist pops back to the list instead of pushing it`() {
        val nav = FakeNavStack(Root, Iptv)
        nav.navigation.openPlaylistForm()
        assertEquals(listOf(Root, Iptv, IptvAddPlaylist), nav.stack, "Add Playlist opens forward")

        nav.navigation.playlistFormDone()

        assertEquals(listOf(Root, Iptv), nav.stack, "save returns to the list - the form is gone")
    }

    @Test
    fun `saving an edited playlist also pops exactly one page`() {
        val nav = FakeNavStack(Root, Iptv, IptvAddPlaylist)

        nav.navigation.playlistFormDone()

        assertEquals(listOf(Root, Iptv), nav.stack)
    }

    @Test
    fun `a restored content page with no target bounces back to the list`() {
        val nav = FakeNavStack(Root, Iptv, IptvContent)

        nav.navigation.bounceBackAfterRestore()

        assertEquals(listOf(Root, Iptv), nav.stack, "no stale content page left for Back to return to")
    }

    @Test
    fun `a restored checklist with no target unwinds one page at a time`() {
        val nav = FakeNavStack(Root, Iptv, IptvContent, IptvCategoryChecklist)

        nav.navigation.bounceBackAfterRestore()
        assertEquals(listOf(Root, Iptv, IptvContent), nav.stack, "checklist pops to its parent")
        nav.navigation.bounceBackAfterRestore() // the restored content page bounces in turn

        assertEquals(listOf(Root, Iptv), nav.stack)
    }
}
