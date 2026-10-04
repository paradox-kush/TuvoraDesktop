package com.nuvio.app.features.iptv.overlay

import com.nuvio.app.features.iptv.overlay.IptvChannelQuickActionsPolicy.Action
import com.nuvio.app.features.iptv.overlay.IptvChannelQuickActionsPolicy.HideTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** UX36 + UX73: one long-press menu for a live channel in the guide and the hub, and a hide the hub honours. */
class IptvChannelQuickActionsPolicyTest {

    private data class Card(val id: String)

    @Test
    fun long_press_offers_favourite_then_hide_for_a_channel_with_an_identity() {
        val target = HideTarget("fp:1:abc", "pl")
        assertEquals(listOf(Action.ADD_FAVORITE, Action.HIDE), IptvChannelQuickActionsPolicy.menu(isFavorite = false, hideTarget = target))
        assertEquals(listOf(Action.REMOVE_FAVORITE, Action.HIDE), IptvChannelQuickActionsPolicy.menu(isFavorite = true, hideTarget = target))
    }

    @Test
    fun hide_is_not_offered_when_the_channel_has_no_identity_to_key_it_on() {
        assertEquals(listOf(Action.ADD_FAVORITE), IptvChannelQuickActionsPolicy.menu(isFavorite = false, hideTarget = null))
        assertNull(IptvChannelQuickActionsPolicy.hideTarget(entityId = "", playlistId = "pl"))
        assertNull(IptvChannelQuickActionsPolicy.hideTarget(entityId = null, playlistId = "pl"))
        assertEquals(HideTarget("fp:1:abc", "pl"), IptvChannelQuickActionsPolicy.hideTarget("fp:1:abc", "pl"))
    }

    @Test
    fun the_hub_offers_hide_on_provider_rows_but_not_on_the_favourites_or_recents_rails() {
        val target = HideTarget("fp:1:abc", "pl")
        assertEquals(target, IptvChannelQuickActionsPolicy.hubHideTarget(target, inPersonalRail = false))
        assertNull(IptvChannelQuickActionsPolicy.hubHideTarget(target, inPersonalRail = true))
    }

    @Test
    fun a_channel_hidden_after_its_hub_row_loaded_drops_out_of_the_row() {
        // The old hub filtered hides only when a row was fetched, so a hide made from the guide (or
        // the hub) left the channel in every row already on screen (UX73).
        val items = listOf(Card("a"), Card("b"), Card("c"))
        val entities = mapOf("a" to "e-a", "b" to "e-b", "c" to "e-c")
        val overlay = mapOf("e-b" to ChannelOverlay(hidden = true), "e-c" to ChannelOverlay(pinned = true))
        assertEquals(listOf(Card("a"), Card("c")), IptvChannelQuickActionsPolicy.visibleInHub(items, overlay) { entities[it.id] })
    }

    @Test
    fun undoing_the_hide_brings_the_channel_back_into_the_row() {
        val items = listOf(Card("a"), Card("b"))
        val entities = mapOf("a" to "e-a", "b" to "e-b")
        val undone = mapOf("e-b" to ChannelOverlay(hidden = false))
        assertEquals(items, IptvChannelQuickActionsPolicy.visibleInHub(items, undone) { entities[it.id] })
    }

    @Test
    fun a_card_whose_identity_is_unknown_is_kept_and_an_untouched_row_is_returned_as_is() {
        val items = listOf(Card("a"), Card("x"))
        val overlay = mapOf("e-a" to ChannelOverlay(hidden = true))
        assertEquals(listOf(Card("x")), IptvChannelQuickActionsPolicy.visibleInHub(items, overlay) { if (it.id == "a") "e-a" else null })
        assertSame(items, IptvChannelQuickActionsPolicy.visibleInHub(items, mapOf("e-a" to ChannelOverlay(pinned = true))) { "e-a" })
    }
}

/**
 * K9 (wave 2): hide a channel in the hub, then Undo it (or unhide it in Settings) — the card must
 * come back in the row, in its provider position, without a re-fetch. A row fetched while the
 * channel was hidden used to cache the window WITHOUT it, so the unhide had nothing to restore
 * until the app was relaunched. Since B108 the hub caches the raw provider window and applies the
 * whole overlay when the row is shown ([IptvChannelQuickActionsPolicy.hubRow]).
 */
class IptvHubRowUnhideTest {

    private data class Card(val id: String, val name: String = id)

    private val lineup = listOf(Card("sky-news"), Card("sky-sports"), Card("sky-movies"))
    private val entities = mapOf("sky-news" to "e-news", "sky-sports" to "e-sports", "sky-movies" to "e-movies")
    private val hidden = mapOf("e-news" to ChannelOverlay(hidden = true))
    private val unhidden = mapOf("e-news" to ChannelOverlay(hidden = false))

    private fun shown(overlay: Map<String, ChannelOverlay>) =
        IptvChannelQuickActionsPolicy.hubRow(lineup, overlay, entityOf = { entities[it.id] }, withName = { c, n -> c.copy(name = n) })

    @Test
    fun `a row loaded while a channel is hidden still shows it once unhidden`() {
        assertEquals(listOf(Card("sky-sports"), Card("sky-movies")), shown(hidden), "the hidden channel must stay out of the row")
        assertEquals(lineup, shown(unhidden), "unhide must restore the card in provider order without a re-fetch")
    }

    @Test
    fun `hide then undo restores the card in its original position`() {
        assertEquals(listOf(Card("sky-sports"), Card("sky-movies")), shown(hidden))
        assertEquals(lineup, shown(unhidden))
        assertEquals(lineup, shown(emptyMap()), "a hide that was deleted outright restores the card too")
    }

    @Test
    fun `the shown row applies renames floats pins and drops hides`() {
        val overlay = mapOf(
            "e-movies" to ChannelOverlay(pinned = true),
            "e-sports" to ChannelOverlay(rename = "Sports"),
            "e-news" to ChannelOverlay(hidden = true),
        )
        assertEquals(listOf(Card("sky-movies"), Card("sky-sports", "Sports")), shown(overlay))
    }
}
