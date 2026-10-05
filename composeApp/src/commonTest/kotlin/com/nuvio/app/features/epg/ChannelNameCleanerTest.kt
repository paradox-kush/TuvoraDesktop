package com.nuvio.app.features.epg

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F10 golden vectors. The SAME table lives in NuvioTV's ChannelNameCleanerTest (JUnit, argument
 * order differs — ported by hand): change one, change both.
 */
class ChannelNameCleanerTest {

    /** raw -> cleaned with the default rules. */
    private val golden = listOf(
        "UK: BBC One HD" to "BBC One",
        "UK | FHD | BBC One" to "BBC One",
        "|UK| SKY SPORTS F1 ᴴᴰ" to "SKY SPORTS F1",
        "[UK] Sky Atlantic FHD" to "Sky Atlantic",
        "(US) ESPN 2 4K" to "ESPN 2",
        "US| FOX SPORTS UHD" to "FOX SPORTS",
        "IN| Star Plus HD" to "Star Plus",
        // language labels panels use like a country prefix (T8: "|EN| CNN" read "EN| CNN")
        "|EN| CNN" to "CNN",
        "ENG: Sky News" to "Sky News",
        "FR ▎ TF1 HEVC" to "TF1",
        "DE: Das Erste H.265" to "Das Erste",
        "EX-YU: RTS 1" to "RTS 1",
        "UKHD: ITV 2" to "ITV 2",
        "UK: ITV +1 HD" to "ITV +1",
        "BBC One (1080p) [Not 24/7]" to "BBC One",
        "Aathavan TV (720p) [Geo-blocked]" to "Aathavan TV",
        "Sky Sports Main Event ★" to "Sky Sports Main Event",
        "⚽ beIN SPORTS 1 ⚽" to "beIN SPORTS 1",
        "Sky Cinema ʀᴀᴡ" to "Sky Cinema",
        "CNN International" to "CNN International",
        // identity survives
        "AL JAZEERA" to "AL JAZEERA",
        "Fox News (East)" to "Fox News (East)",
        "Disney Plus" to "Disney Plus",
        "HD" to "HD",
        "4K" to "4K",
        "UK: HD" to "HD",
        "Channel 4 HD+" to "Channel 4 HD+",
        "TOGGO plus -HD" to "TOGGO plus",
        "  " to "",
    )

    @Test
    fun goldenVectorsWithDefaultRules() {
        for ((raw, want) in golden) {
            assertEquals(want, ChannelNameCleaner.clean(raw), "clean(\"$raw\")")
        }
    }

    @Test
    fun userTagsStripWholeWordsAndLiterals() {
        val rules = ChannelNameCleaner.Rules(userTags = ChannelNameCleaner.parseTags("VIP, |PRIME|\nmulti"))
        assertEquals("Sky Cinema", ChannelNameCleaner.clean("VIP Sky Cinema |PRIME|", rules))
        assertEquals("Multiverse TV", ChannelNameCleaner.clean("Multiverse TV MULTI", rules))
        assertEquals("VIPER TV", ChannelNameCleaner.clean("VIPER TV", rules))
    }

    @Test
    fun eachRuleCanBeTurnedOff() {
        val raw = "UK: BBC One HD ★"
        assertEquals("BBC One HD", ChannelNameCleaner.clean(raw, ChannelNameCleaner.Rules(stripQuality = false)))
        assertEquals("UK: BBC One", ChannelNameCleaner.clean(raw, ChannelNameCleaner.Rules(stripCountryPrefix = false)))
        assertEquals("BBC One ★", ChannelNameCleaner.clean(raw, ChannelNameCleaner.Rules(stripDecorations = false)))
    }

    @Test
    fun parseTagsSplitsAndDedupes() {
        assertEquals(listOf("VIP", "|PRIME|", "RAW"), ChannelNameCleaner.parseTags(" VIP ,|PRIME|;RAW\n\nVIP "))
        assertEquals(emptyList(), ChannelNameCleaner.parseTags(null))
    }
}
