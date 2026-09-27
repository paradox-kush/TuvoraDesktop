package com.nuvio.app.features.iptv.overlay

import com.nuvio.app.features.iptv.identity.IptvIdentity
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.CatalogCategory
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.CatalogChannel
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.HiddenKind
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.NamedCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class IptvHiddenItemsPolicyTest {

    private val pl = "http://panel.example|user"
    private fun catKey(name: String, type: String = "live") = IptvIdentity.categoryKey(pl, type, name)

    private data class Ch(val id: Int, val cat: String?)

    @Test
    fun hidden_category_ids_are_matched_by_name_within_the_playlist_and_type() {
        val overlay = mapOf(catKey("Sports") to CategoryOverlay(hidden = true), catKey("News") to CategoryOverlay(pinned = true))
        val cats = listOf(NamedCategory("1", "Sports"), NamedCategory("2", "News"), NamedCategory("3", "Kids"))
        assertEquals(setOf("1"), IptvHiddenItemsPolicy.hiddenCategoryIds(pl, "live", cats, overlay))
        assertEquals(emptySet(), IptvHiddenItemsPolicy.hiddenCategoryIds(pl, "movies", cats, overlay), "a live hide does not hide a movie category")
        assertEquals(emptySet(), IptvHiddenItemsPolicy.hiddenCategoryIds("other|user", "live", cats, overlay), "another playlist's hide does not apply")
    }

    @Test
    fun the_guide_drops_channels_of_hidden_and_deselected_categories() {
        val channels = listOf(Ch(1, "1"), Ch(2, "2"), Ch(3, "3"), Ch(4, null))
        val shown = IptvHiddenItemsPolicy.guideChannels(
            channels, hiddenCategoryIds = setOf("1"), allowedBySelection = { it != "3" }, categoryOf = { it.cat },
        )
        assertEquals(listOf(2, 4), shown.map { it.id })
    }

    @Test
    fun the_guide_is_untouched_when_nothing_is_hidden_or_deselected() {
        val channels = listOf(Ch(1, "1"), Ch(2, null))
        assertEquals(channels, IptvHiddenItemsPolicy.guideChannels(channels, emptySet(), { true }, { it.cat }))
    }

    @Test
    fun the_hidden_list_names_groups_first_then_channels_alphabetically() {
        val bbc = IptvIdentity.entityId(pl, "BBC One", null)
        val abc = IptvIdentity.entityId(pl, "ABC", null)
        val shown = IptvIdentity.entityId(pl, "CNN", null)
        val overlay = OverlaySnapshot(
            channels = mapOf(bbc to ChannelOverlay(hidden = true), abc to ChannelOverlay(hidden = true), shown to ChannelOverlay(pinned = true)),
            categories = mapOf(catKey("Sports") to CategoryOverlay(hidden = true), catKey("Horror", "movies") to CategoryOverlay(hidden = true)),
        )
        val items = IptvHiddenItemsPolicy.hiddenItems(
            channels = listOf(CatalogChannel(bbc, "BBC One"), CatalogChannel(shown, "CNN"), CatalogChannel(abc, "ABC")),
            categories = listOf(
                CatalogCategory("live", catKey("Sports"), "Sports"),
                CatalogCategory("live", catKey("News"), "News"),
                CatalogCategory("movies", catKey("Horror", "movies"), "Horror"),
            ),
            overlay = overlay,
        )
        assertEquals(
            listOf(HiddenKind.GROUP to "Sports", HiddenKind.GROUP to "Horror", HiddenKind.CHANNEL to "ABC", HiddenKind.CHANNEL to "BBC One"),
            items.map { it.kind to it.name },
        )
        assertEquals(listOf("live", "movies", "live", "live"), items.map { it.contentType })
    }

    @Test
    fun a_renamed_hidden_item_is_listed_under_the_name_the_viewer_gave_it() {
        val e = IptvIdentity.entityId(pl, "UK: BBC ONE FHD", null)
        val items = IptvHiddenItemsPolicy.hiddenItems(
            channels = listOf(CatalogChannel(e, "UK: BBC ONE FHD")),
            categories = emptyList(),
            overlay = OverlaySnapshot(channels = mapOf(e to ChannelOverlay(hidden = true, rename = "BBC One"))),
        )
        assertEquals(listOf("BBC One"), items.map { it.name })
    }

    @Test
    fun a_channel_listed_twice_in_the_catalog_appears_once() {
        val e = IptvIdentity.entityId(pl, "BBC One", null)
        val items = IptvHiddenItemsPolicy.hiddenItems(
            channels = listOf(CatalogChannel(e, "BBC One"), CatalogChannel(e, "BBC One")),
            categories = emptyList(),
            overlay = OverlaySnapshot(channels = mapOf(e to ChannelOverlay(hidden = true))),
        )
        assertEquals(1, items.size)
    }
}
