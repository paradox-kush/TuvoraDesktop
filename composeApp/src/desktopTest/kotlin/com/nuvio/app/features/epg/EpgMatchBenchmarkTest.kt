package com.nuvio.app.features.epg

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Lane G performance gate: the B10 matcher on a 50,000-channel synthetic lineup against a
 * 30,000-channel guide, on the JVM. The ingest runs it once per guide download on a background
 * scope (never the main thread); this pins that one run stays a small fraction of the download
 * itself. The 32-bit Onn is ~10x slower than this host, so the bound is kept far below the 60 s
 * the 49k-channel fuzzy re-match cost there (tuvora-tv-onn-background-spin) — fuzzy is OFF on the
 * store lane, and the second number shows what it would add.
 */
class EpgMatchBenchmarkTest {

    private val words = listOf(
        "sky", "sports", "news", "cinema", "movies", "bbc", "itv", "channel", "discovery", "history",
        "nat", "geo", "wild", "cartoon", "network", "comedy", "central", "fox", "espn", "eurosport",
        "tnt", "premier", "league", "golf", "f1", "action", "drama", "kids", "music", "hits", "family",
        "plus", "one", "two", "arena", "max", "prime", "star", "life", "food", "travel", "science",
    )
    private val prefixes = listOf("", "UK: ", "|US| ", "FR ▎", "DE: ", "[IT] ", "IN| ", "UKHD: ", "ES - ")
    private val suffixes = listOf("", " HD", " FHD", " 4K", " ᴴᴰ", " (1080p)", " HEVC", " SD", " +1")

    private fun base(r: Random) = (1..r.nextInt(2, 4)).joinToString(" ") { words[r.nextInt(words.size)] } +
        if (r.nextInt(3) == 0) " ${r.nextInt(1, 12)}" else ""

    @Test
    fun fiftyThousandChannelLineupMatchesQuickly() {
        val r = Random(42)
        val guide = (0 until 30_000).map { i ->
            val name = base(r).split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            GuideChannelMatcher.GuideChannel("ch$i.xx", listOf(name))
        }
        val lineup = (0 until 50_000).map { i ->
            val name = if (i % 2 == 0) guide[r.nextInt(guide.size)].names.first() else base(r)
            GuideChannelMatcher.LineupChannel(i, prefixes[r.nextInt(prefixes.size)] + name + suffixes[r.nextInt(suffixes.size)], epgId = null)
        }
        GuideChannelMatcher.match(lineup.take(2_000), guide) // JIT warm-up
        val t0 = System.nanoTime()
        val res = GuideChannelMatcher.match(lineup, guide)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val fuzzy = GuideChannelMatcher.match(lineup, guide, allowFuzzy = true)
        val fuzzyMs = (System.nanoTime() - t1) / 1_000_000
        println("EpgMatchBenchmark: 50,000 x 30,000 -> matched ${res.assignments.size} in $ms ms (fuzzy on: ${fuzzy.assignments.size} in $fuzzyMs ms)")
        assertTrue(ms < 5_000, "B10 matcher took $ms ms for 50k channels on the JVM")
    }
}
