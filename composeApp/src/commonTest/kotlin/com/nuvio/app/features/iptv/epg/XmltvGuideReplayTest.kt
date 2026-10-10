package com.nuvio.app.features.iptv.epg

import com.nuvio.app.features.epg.ChannelNameCleaner
import com.nuvio.app.features.epg.GuideChannelMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XmltvGuideReplayTest {
    private val start = "20261010120000 +0000"
    private val end = "20261010130000 +0000"
    private val now = parseXmltvTime(start)!! + 1
    private fun ch(id: String, name: String) = "<channel id=\"$id\"><display-name>$name</display-name></channel>"
    private fun prog(id: String, title: String) = "<programme channel=\"$id\" start=\"$start\" stop=\"$end\"><title>$title</title></programme>"

    @Test
    fun completeCensusHandlesInterleavingLateDefinitionsAndExactIdPrecedence() {
        val replay = XmltvGuideReplay(listOf(
            GuideChannelMatcher.LineupChannel(1, "CNN", "late"),
            GuideChannelMatcher.LineupChannel(2, "Інтер HD", null),
        ), setOf("manual"), ChannelNameCleaner.Rules.DEFAULT, now)
        try {
            val xml = "<tv>\r\n" + ch("early", "CNN") + prog("early", "Wrong") +
                prog("late", "Correct") + ch("late", "CNN") +
                ch("inter", "Інтер") + prog("inter", "Новини &amp; погода") +
                ch("manual", "Unrelated") + prog("manual", "Picked") +
                ch("unused", "Unused") + prog("unused", "Discard") + "</tv>"
            // Chunk boundaries deliberately split tag names, attributes and Unicode text.
            xml.chunked(7).forEach(replay::feed)
            val rows = mutableListOf<XmltvProgramme>()
            val result = replay.finish(rows::add)
            assertEquals(listOf("late", "inter"), result.assignments.map { it.guideId })
            assertEquals(listOf("Correct", "Новини & погода", "Picked"), rows.map { it.title })
        } finally { replay.dispose() }
    }

    @Test
    fun minifiedGuideChunksPreserveSupplementaryUnicodeAtRecordBoundary() {
        val replay = XmltvGuideReplay(listOf(GuideChannelMatcher.LineupChannel(1, "CNN", "cnn")), emptySet(), ChannelNameCleaner.Rules.DEFAULT, now)
        try {
            val prefix = "<tv><!--"
            val padding = "x".repeat(16 * 1024 - prefix.length - 1)
            replay.feed(prefix + padding + "📺" + "x".repeat(32 * 1024) + "-->" +
                ch("cnn", "CNN") + prog("cnn", "News 📺") + "</tv>")
            val rows = mutableListOf<XmltvProgramme>()
            replay.finish(rows::add)
            assertEquals(listOf("News 📺"), rows.map { it.title })
        } finally { replay.dispose() }
    }

    @Test
    fun replaySizeIsBoundedAndCleanupIsIdempotent() {
        val replay = XmltvGuideReplay(emptyList(), emptySet(), ChannelNameCleaner.Rules.DEFAULT, now, maxChars = 10)
        try { assertFailsWith<IllegalStateException> { replay.feed("x".repeat(20)) } }
        finally { replay.dispose(); replay.dispose() }
    }

    @Test
    fun unknownChannelsAndOutOfWindowProgrammesDoNotReachTheStore() {
        val replay = XmltvGuideReplay(listOf(GuideChannelMatcher.LineupChannel(1, "CNN", "cnn")), emptySet(), ChannelNameCleaner.Rules.DEFAULT, now)
        try {
            replay.feed("<tv>" + ch("cnn", "CNN") + prog("cnn", "Current") +
                prog("cnn", "Old").replace("20261010", "20200101") + prog("unknown", "Unknown") + "</tv>")
            val rows = mutableListOf<XmltvProgramme>()
            replay.finish(rows::add)
            assertEquals(listOf("Current"), rows.map { it.title })
        } finally { replay.dispose() }
    }
}
