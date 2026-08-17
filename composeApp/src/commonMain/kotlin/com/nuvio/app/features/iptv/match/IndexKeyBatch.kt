package com.nuvio.app.features.iptv.match

/** One row destined for the `keys` table. */
internal data class IndexKeyRow(val key: String, val sid: Int)

/**
 * Every `keys` row for [items], ordered the way the table stores them.
 *
 * `keys` is declared `WITHOUT ROWID` with `PRIMARY KEY(provider, kind, k, sid)`, so the primary key
 * *is* the storage B-tree — there is no separate heap. `provider` and `kind` are constant within a
 * write, which leaves `k` (a normalised title) as the leading variable column. Feeding rows in
 * catalog order therefore lands each insert at an essentially random leaf: page splits, no write
 * locality, and a working set far larger than the page cache. Over a real catalog that is ~2 million
 * randomly-placed inserts, and a prime suspect for the ~17-minute cold build measured on 468,425
 * items (research/iptv-index-build-cost.md).
 *
 * Sorting by `(k, sid)` first makes the batch arrive in B-tree order, which is what SQLite is fast
 * at. Sorting a few hundred strings is free next to the page churn it avoids.
 *
 * Ordering only — the set of (key, sid) pairs is exactly what [TitleNormalizer.keysOf] produced,
 * which `IndexKeyBatchTest` pins.
 */
internal fun sortedKeyRows(items: List<IndexedItem>): List<IndexKeyRow> =
    items.flatMap { item -> TitleNormalizer.keysOf(item.name).map { IndexKeyRow(it, item.sid) } }
        .sortedWith(compareBy({ it.key }, { it.sid }))
