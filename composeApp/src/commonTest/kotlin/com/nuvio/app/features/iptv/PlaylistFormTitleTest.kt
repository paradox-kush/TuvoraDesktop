package com.nuvio.app.features.iptv

import com.nuvio.app.features.settings.SettingsPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * UX19 — the reused Add-Playlist page is titled "Edit Playlist" while editing. The phone navigator
 * used to pass a title map built during composition, BEFORE the click handler switched the page to
 * edit mode, so it always said "Add Playlist". The override must read the mode at call time.
 */
class PlaylistFormTitleTest {

    @Test
    fun `the edit title follows the mode at navigation time - not at composition time`() {
        var editing = false
        val override = playlistFormTitleOverride("Edit Playlist") { editing }
        assertNull(override(SettingsPage.IptvAddPlaylist), "add mode keeps the static Add Playlist title")
        editing = true   // what openEdit() does in the click handler, after composition
        assertEquals("Edit Playlist", override(SettingsPage.IptvAddPlaylist))
        assertNull(override(SettingsPage.Iptv), "other pages are never overridden")
    }
}
