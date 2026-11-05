package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvLiveCacheTest {
    @Test
    fun `plan becomes bytes back bytes and cache secs`() {
        assertEquals(
            listOf("demuxer-max-bytes" to "200", "demuxer-max-back-bytes" to "100", "cache-secs" to "90"),
            MpvLiveCache.properties(200, 100, 90),
        )
    }

    @Test
    fun `an unknown cache secs leaves the engine default alone and negatives clamp`() {
        assertEquals(
            listOf("demuxer-max-bytes" to "0", "demuxer-max-back-bytes" to "5"),
            MpvLiveCache.properties(-1, 5, 0),
        )
    }

    @Test
    fun `the window is the range holding the playhead`() {
        val ranges = listOf(MpvLiveCache.Range(10.0, 20.0), MpvLiveCache.Range(100.0, 160.0))
        assertEquals(LiveSeekWindow(100_000, 160_000, 140_000), MpvLiveCache.window(ranges, 140_000))
        assertEquals(LiveSeekWindow(10_000, 20_000, 15_000), MpvLiveCache.window(ranges, 15_000))
    }

    @Test
    fun `a playhead outside every range reports the newest range with the position clamped`() {
        val ranges = listOf(MpvLiveCache.Range(100.0, 160.0))
        assertEquals(LiveSeekWindow(100_000, 160_000, 160_000), MpvLiveCache.window(ranges, 170_000))
    }

    @Test
    fun `nothing seekable or no position is no window`() {
        assertNull(MpvLiveCache.window(emptyList(), 5_000))
        assertNull(MpvLiveCache.window(listOf(MpvLiveCache.Range(0.0, 10.0)), 0))
        assertNull(MpvLiveCache.window(listOf(MpvLiveCache.Range(5.0, 5.0), MpvLiveCache.Range(Double.NaN, 3.0)), 1_000))
    }

    @Test
    fun `behind live is the distance to the end of the window`() {
        assertEquals(20_000, LiveSeekWindow(0, 60_000, 40_000).behindLiveMs)
        assertEquals(0, LiveSeekWindow(0, 60_000, 70_000).behindLiveMs)
    }

    @Test
    fun `demuxer cache state json yields its seekable ranges`() {
        val json = """{"cache-end":172.5,"reader-pts":140.2,"fw-bytes":1048576,"seekable-ranges":[{"start":100,"end":160.5},{"start":165.25,"end":172.5}],"bof-cached":false}"""
        assertEquals(
            listOf(MpvLiveCache.Range(100.0, 160.5), MpvLiveCache.Range(165.25, 172.5)),
            MpvLiveCache.parseSeekableRanges(json),
        )
    }

    @Test
    fun `an empty missing or malformed demuxer cache state is no ranges not a crash`() {
        assertEquals(emptyList(), MpvLiveCache.parseSeekableRanges(null))
        assertEquals(emptyList(), MpvLiveCache.parseSeekableRanges(""))
        assertEquals(emptyList(), MpvLiveCache.parseSeekableRanges("{not-json"))
        assertEquals(emptyList(), MpvLiveCache.parseSeekableRanges("42"))
        assertEquals(emptyList(), MpvLiveCache.parseSeekableRanges("{}"))
        assertEquals(emptyList(), MpvLiveCache.parseSeekableRanges("""{"seekable-ranges":[]}"""))
    }

    @Test
    fun `a range entry missing a bound is skipped and the rest kept`() {
        val json = """{"seekable-ranges":[{"start":1},{"start":2,"end":9},{"end":3},{"start":"x","end":4}]}"""
        assertEquals(listOf(MpvLiveCache.Range(2.0, 9.0)), MpvLiveCache.parseSeekableRanges(json))
    }
}
