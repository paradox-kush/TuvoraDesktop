package com.nuvio.app.features.epg

import com.nuvio.app.features.epg.GuideChannelMatcher.GuideChannel
import com.nuvio.app.features.epg.GuideChannelMatcher.LineupChannel
import com.nuvio.app.features.epg.GuideChannelMatcher.Tier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B10 — the playlist's own guide must be matched by NAME when the id is blank or wrong, the way
 * TiviMate does. Red before this branch: the store lane joined on exact id only, so every
 * blank-id row below resolved to nothing. Twin: NuvioTV GuideChannelMatcherTest (JUnit, by hand).
 */
class GuideChannelMatcherTest {

    private val guide = listOf(
        GuideChannel("bbc1.uk", listOf("BBC One")),
        GuideChannel("starplus.in", listOf("Star Plus")),
        GuideChannel("itv1.uk", listOf("ITV1", "ITV")),
        GuideChannel("itv1plus1.uk", listOf("ITV1 +1", "ITV +1")),
        GuideChannel("SkySp.F1.uk", listOf("Sky Sports F1 HD", "Sky Sports F1")),
        GuideChannel("cnn.us", listOf("CNN")),
    )

    private fun assigned(lineup: List<LineupChannel>) =
        GuideChannelMatcher.match(lineup, guide).assignments.associate { it.streamId to (it.guideId to it.tier) }

    @Test
    fun blankIdChannelsMatchTheGuideByCleanedName() {
        val got = assigned(
            listOf(
                LineupChannel(1, "UK: BBC One FHD", epgId = ""),
                LineupChannel(2, "IN| Star Plus HD", epgId = null),
                LineupChannel(3, "|UK| SKY SPORTS F1 ᴴᴰ", epgId = ""),
            ),
        )
        assertEquals("bbc1.uk" to Tier.NAME, got[1], "country prefix + quality")
        assertEquals("starplus.in" to Tier.NAME, got[2], "pipe prefix")
        assertEquals("skysp.f1.uk" to Tier.NAME, got[3], "styled badge")
    }

    @Test
    fun timeshiftNeverMapsOntoItsBaseChannel() {
        val got = assigned(listOf(LineupChannel(1, "UK: ITV +1", epgId = "")))
        assertEquals("itv1plus1.uk" to Tier.NAME, got[1], "+1 must find the +1 guide channel")
        val onlyBase = GuideChannelMatcher.match(
            listOf(LineupChannel(1, "UK: ITV +1", epgId = "")),
            listOf(GuideChannel("itv1.uk", listOf("ITV1"))),
        )
        assertEquals(emptyList(), onlyBase.assignments, "+1 with no +1 in the guide stays unmatched")
    }

    @Test
    fun providerIdWinsAndIsFoldedLikeToday() {
        val got = assigned(
            listOf(
                LineupChannel(1, "Totally different name", epgId = "CNN.US"),
                LineupChannel(2, "BBC One", epgId = "BBC1.uk@SD"),
            ),
        )
        assertEquals("cnn.us" to Tier.ID, got[1], "case-folded id")
        assertEquals("bbc1.uk" to Tier.ID, got[2], "iptv-org @feed suffix")
    }

    @Test
    fun censusCountsTiersAgainstEligibleChannels() {
        val r = GuideChannelMatcher.match(
            listOf(
                LineupChannel(1, "CNN", epgId = "cnn.us"),
                LineupChannel(2, "UK: BBC One", epgId = ""),
                LineupChannel(3, "===== UK SPORTS =====", epgId = ""),
                LineupChannel(4, "UFC 300 PPV", epgId = ""),
                LineupChannel(5, "Nothing Like It", epgId = ""),
            ),
            guide,
        )
        assertEquals(GuideChannelMatcher.Census(lineup = 5, eligible = 3, id = 1, name = 1, fuzzy = 0), r.census)
    }

    @Test
    fun fuzzyIsOffUnlessAsked() {
        val lineup = listOf(LineupChannel(1, "Sky Sports F1", epgId = ""))
        val g = listOf(GuideChannel("x", listOf("Sky Sportz F1")))
        assertEquals(emptyList(), GuideChannelMatcher.match(lineup, g).assignments)
        assertEquals(Tier.FUZZY, GuideChannelMatcher.match(lineup, g, allowFuzzy = true).assignments.single().tier)
    }

    @Test
    fun unicodeNamesKeepTheirIdentity() {
        assertEquals("itv +1", EpgNorm.baseNorm("ITV +1"))
        val names = listOf("Суспільне Спорт", "Футбол 1", "Інтер", "ΕΡΤ", "日本テレビ")
        val lineup = names.mapIndexed { i, n -> GuideChannelMatcher.LineupChannel(i, "$n HD", null) }
        val guide = names.mapIndexed { i, n -> GuideChannelMatcher.GuideChannel("guide-$i", listOf(n)) }
        val result = GuideChannelMatcher.match(lineup, guide)
        assertEquals(names.size, result.assignments.size)
        names.indices.forEach { i -> assertEquals("guide-$i", result.assignments.single { it.streamId == i }.guideId) }
        assertEquals("інтер", EpgNorm.coreNorm("Інтер HD"))
    }
}
