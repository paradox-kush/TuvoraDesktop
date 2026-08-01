package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodeBucketsTest {

    @Test
    fun `short seasons are not bucketed`() {
        assertTrue(episodeBuckets(episodes(1..20)).isEmpty())
        assertTrue(episodeBuckets(episodes(1..60)).isEmpty())
    }

    @Test
    fun `a thousand episode season splits into labelled ranges`() {
        val buckets = episodeBuckets(episodes(1..1042))

        assertEquals(21, buckets.size)
        assertEquals("1-50", buckets.first().label)
        assertEquals("1001-1042", buckets.last().label)
        assertEquals(42, buckets.last().size)
        assertEquals(1042, buckets.sumOf { it.size })
    }

    @Test
    fun `buckets tile the season with no gaps or overlaps`() {
        val buckets = episodeBuckets(episodes(1..1042))
        buckets.zipWithNext { a, b -> assertEquals(a.untilIndex, b.fromIndex) }
        assertEquals(0, buckets.first().fromIndex)
        assertEquals(1042, buckets.last().untilIndex)
    }

    @Test
    fun `sparse provider numbering still yields evenly sized buckets`() {
        // Xtream providers skip numbers; slicing by episode arithmetic would leave ranges empty.
        val sparse = (1..200).map { video(it * 7) }
        val buckets = episodeBuckets(sparse)

        assertEquals(4, buckets.size)
        buckets.forEach { assertEquals(50, it.size) }
        assertEquals("7-350", buckets.first().label)
    }

    @Test
    fun `episodes with no number fall back to positional labels`() {
        val unnumbered = List(120) { MetaVideo(id = "e$it", title = "Episode $it") }
        val buckets = episodeBuckets(unnumbered)

        assertEquals(3, buckets.size)
        assertEquals("1-50", buckets.first().label)
        assertEquals("101-120", buckets.last().label)
    }

    @Test
    fun `duplicate numbering does not collapse buckets`() {
        val duplicated = (1..150).map { video(it / 2) }
        val buckets = episodeBuckets(duplicated)

        assertEquals(3, buckets.size)
        assertEquals(150, buckets.sumOf { it.size })
    }

    @Test
    fun `single episode slice reads as one number`() {
        val buckets = episodeBuckets(episodes(1..101))
        assertEquals("101", buckets.last().label)
        assertEquals(1, buckets.last().size)
    }

    @Test
    fun `bucketContaining finds the slice holding an episode and falls back to the first`() {
        val buckets = episodeBuckets(episodes(1..1042))

        assertEquals("801-850", buckets.bucketContaining(846)?.label)
        assertEquals("1-50", buckets.bucketContaining(0)?.label)
        assertEquals("1-50", buckets.bucketContaining(-1)?.label)
        assertEquals("1-50", buckets.bucketContaining(99_999)?.label)
    }

    private fun episodes(range: IntRange): List<MetaVideo> = range.map(::video)

    private fun video(episode: Int): MetaVideo =
        MetaVideo(id = "e$episode", title = "Episode $episode", season = 1, episode = episode)
}
