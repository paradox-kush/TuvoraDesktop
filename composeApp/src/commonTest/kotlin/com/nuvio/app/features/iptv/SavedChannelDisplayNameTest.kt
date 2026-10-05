package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

class SavedChannelDisplayNameTest {
    private val clean = XtreamAccount(
        id = "m3u|http://h/get.php", name = "P", baseUrl = "http://h/get.php", username = "", password = "",
        cleanChannelNames = true,
    )
    private val raw = clean.copy(id = "http://x:80|u", cleanChannelNames = false)

    @Test
    fun favouriteOfACleanedPlaylistShowsTheCleanedName() {
        // F10 (device pass 2026-10-05): Favorites / Recent rows showed "|UK| Channel 4 HD" while the
        // category rows of the same playlist showed "Channel 4".
        val id = XtreamItemRegistry.liveId(clean.id, 7)
        assertEquals("Channel 4", savedChannelDisplayName("|UK| Channel 4 HD", id, listOf(raw, clean)))
    }

    @Test
    fun favouriteOfAnUncleanedOrUnknownPlaylistKeepsItsName() {
        assertEquals("|UK| Channel 4 HD", savedChannelDisplayName("|UK| Channel 4 HD", XtreamItemRegistry.liveId(raw.id, 7), listOf(raw, clean)))
        assertEquals("|UK| Channel 4 HD", savedChannelDisplayName("|UK| Channel 4 HD", XtreamItemRegistry.liveId("gone|x", 7), listOf(raw, clean)))
        assertEquals("|UK| Channel 4 HD", savedChannelDisplayName("|UK| Channel 4 HD", "tt0133093", listOf(raw, clean)))
    }
}
