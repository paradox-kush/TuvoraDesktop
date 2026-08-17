package com.nuvio.app.features.iptv.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `keys` table is `WITHOUT ROWID` with `PRIMARY KEY(provider, kind, k, sid)`, which means the
 * primary key IS the storage B-tree. `k` is a normalised title, so inserting a catalog's ~2 million
 * key rows in catalog order lands them at random leaves: page splits, no write locality, and a
 * working set far past the page cache. That is the textbook random-insert pathology and a prime
 * suspect for the ~17-minute cold build over 468,425 items (research/iptv-index-build-cost.md).
 *
 * Sorting a batch by `k` first makes the writes arrive in B-tree order. Sorting a few hundred
 * strings costs nothing next to what it saves.
 */
class IndexKeyBatchTest {

    private fun item(sid: Int, name: String) = IndexedItem(sid = sid, name = name, year = null, tmdb = null, ext = null)

    @Test
    fun `key rows are emitted in primary-key order`() {
        val rows = sortedKeyRows(listOf(item(3, "Zulu"), item(1, "Alpha"), item(2, "Mike")))

        assertEquals(rows.map { it.key }, rows.map { it.key }.sorted(), "rows must ascend by key")
        assertTrue(rows.isNotEmpty())
    }

    /** Ties on `k` must then ascend by `sid` — the next primary-key column. */
    @Test
    fun `rows with the same key ascend by sid`() {
        val rows = sortedKeyRows(listOf(item(9, "Heat"), item(2, "Heat"), item(5, "Heat")))
            .filter { it.key == "heat" }

        assertEquals(listOf(2, 5, 9), rows.map { it.sid }, "same key must ascend by sid")
    }

    /** Sorting must not lose, add, or alter a single (key, sid) pair — only reorder them. */
    @Test
    fun `sorting preserves exactly the pairs keysOf produces`() {
        val items = listOf(item(1, "The Matrix (1999)"), item(2, "[REC] (2007)"), item(3, "96.Ikiru.1952"))

        val expected = items.flatMap { item -> TitleNormalizer.keysOf(item.name).map { it to item.sid } }.toSet()
        val actual = sortedKeyRows(items).map { it.key to it.sid }.toSet()

        assertEquals(expected, actual, "sorting must reorder, never change the set")
    }

    @Test
    fun `an empty batch yields no rows`() {
        assertEquals(emptyList(), sortedKeyRows(emptyList()))
    }

    /** A blank name yields no keys, and must not produce a phantom empty-key row. */
    @Test
    fun `items with no keys contribute nothing`() {
        val rows = sortedKeyRows(listOf(item(1, "   "), item(2, "Dune")))

        assertTrue(rows.none { it.sid == 1 }, "blank name must not emit a row")
        assertTrue(rows.any { it.sid == 2 }, "a real name still does")
        assertTrue(rows.none { it.key.isEmpty() }, "no empty keys")
    }
}
