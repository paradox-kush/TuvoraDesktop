package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.GENERIC_LIVE_CHANNEL_NAME
import com.nuvio.app.core.contracts.LiveChannelInfo
import com.nuvio.app.core.contracts.LiveChannelNames
import com.nuvio.app.features.library.LibraryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B64 device pass T3: some channels' saved name became "Live TV". A Favorites / Recent card of a channel
 * the in-memory registry had not seen (after the re-key: every saved channel) launched titled with the
 * generic fallback, and playing it re-recorded the recent under that title; the re-key then kept the
 * newest — placeholder — copy. The launch now takes the saved name, a placeholder never replaces a real
 * name, and a re-key merge keeps the real one.
 */
class LiveChannelSavedNameTest {

    private val id = "xtream:m3u|http://h/get.php|u00000000:live:42"

    @Test
    fun theGenericFallbackIsNotAName() {
        assertFalse(LiveChannelNames.isKnown(GENERIC_LIVE_CHANNEL_NAME))
        assertFalse(LiveChannelNames.isKnown(" "))
        assertFalse(LiveChannelNames.isKnown(null))
        assertTrue(LiveChannelNames.isKnown("BBC One"))
        assertEquals("BBC One", LiveChannelNames.best(null, GENERIC_LIVE_CHANNEL_NAME, "BBC One", "Other"))
        assertNull(LiveChannelNames.best(GENERIC_LIVE_CHANNEL_NAME, ""))
    }

    @Test
    fun playingWithThePlaceholderTitleKeepsTheRecentsRealName() {
        val before = listOf(XtreamLiveRecent("other", "ITV", null), XtreamLiveRecent(id, "BBC One", "bbc.png"))
        val after = XtreamLiveRecents.recorded(before, XtreamLiveRecent(id, GENERIC_LIVE_CHANNEL_NAME, null), cap = 20)
        assertEquals(listOf(id, "other"), after.map { it.contentId }, "still moves to the front")
        assertEquals("BBC One", after.first().name)
        assertEquals("bbc.png", after.first().logo)
    }

    @Test
    fun aRealNameStillUpdatesTheRecent() {
        val after = XtreamLiveRecents.recorded(listOf(XtreamLiveRecent(id, GENERIC_LIVE_CHANNEL_NAME, null)), XtreamLiveRecent(id, "BBC One", null), cap = 20)
        assertEquals("BBC One", after.single().name)
    }

    @Test
    fun aRekeyMergeKeepsTheRealNameNotTheNewestPlaceholder() {
        val recents = listOf(XtreamLiveRecent("new", GENERIC_LIVE_CHANNEL_NAME, null), XtreamLiveRecent("old", "BBC One", "bbc.png"))
        val (after, moved) = XtreamLiveRecents.rekeyed(recents) { if (it == "old") "new" else null }
        assertEquals(1, moved)
        assertEquals(listOf("new"), after.map { it.contentId })
        assertEquals("BBC One", after.single().name)
        assertEquals("bbc.png", after.single().logo)
    }

    @Test
    fun anUnregisteredSavedChannelLaunchesWithItsSavedName() {
        val favourite = LibraryItem(id = id, type = "tv", name = "BBC One", logo = "bbc.png", savedAtEpochMs = 1)
        val info = LiveChannelInfoFallback.of(registered = null, favourite = favourite, recent = XtreamLiveRecent(id, GENERIC_LIVE_CHANNEL_NAME, null))
        assertEquals("BBC One", info?.name)
        assertEquals("bbc.png", info?.logo)
        assertNull(info?.streamUrl, "resolved at play")
        assertEquals("ITV", LiveChannelInfoFallback.of(null, null, XtreamLiveRecent(id, "ITV", null))?.name)
    }

    @Test
    fun theRegistryStillWinsAndAnUnknownChannelStaysUnknown() {
        val registered = LiveChannelInfo("BBC One HD", "l", null, "http://s")
        assertEquals(registered, LiveChannelInfoFallback.of(registered, LibraryItem(id = id, type = "tv", name = "BBC One", savedAtEpochMs = 1), null))
        assertNull(LiveChannelInfoFallback.of(null, null, XtreamLiveRecent(id, GENERIC_LIVE_CHANNEL_NAME, null)))
    }
}
