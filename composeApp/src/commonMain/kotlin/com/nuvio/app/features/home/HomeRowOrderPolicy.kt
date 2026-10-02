package com.nuvio.app.features.home

/**
 * The order Home draws its catalog and collection rows in (and the order the Home layout settings
 * list them in, so drag indices line up).
 *
 * B81: a collection the user pinned to the top must render above every catalog row, whatever
 * order the stored layout carries. The stored order is synced across devices, and the TV app keeps
 * pinned collections first at render time instead of in that order — so a layout pulled from
 * another device can place a pinned collection between catalog rows. Applying the pin here, on
 * every read, keeps the guarantee no matter which path last wrote the order.
 */
internal object HomeRowOrderPolicy {
    /**
     * Catalog and collection keys sorted by their stored order (unknown order last; ties keep
     * catalogs before collections, each in definition order), with every pinned collection moved
     * to the front. Pinned collections keep their relative order, and so does everything else.
     */
    fun orderedKeys(
        catalogKeys: List<String>,
        collectionKeys: List<String>,
        pinnedCollectionKeys: Set<String>,
        orderOf: (String) -> Int?,
    ): List<String> {
        val byStoredOrder = (catalogKeys + collectionKeys).sortedBy { key -> orderOf(key) ?: Int.MAX_VALUE }
        if (pinnedCollectionKeys.isEmpty()) return byStoredOrder
        val (pinned, rest) = byStoredOrder.partition { key -> key in pinnedCollectionKeys }
        return pinned + rest
    }
}
