package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B81: "collections pinned to the top still render between the metadata rows". The stored order is
 * synced, and a layout from another device (TV keeps pinned rows first at render time, not in the
 * stored order) can interleave a pinned collection with catalog rows.
 */
class HomeRowOrderPolicyTest {
    private val catalogs = listOf("addon:movie:popular", "addon:series:popular", "addon:movie:top")
    private val collections = listOf("collection_studios", "collection_kids")

    private fun ordered(orders: Map<String, Int>, pinned: Set<String>) =
        HomeRowOrderPolicy.orderedKeys(
            catalogKeys = catalogs,
            collectionKeys = collections,
            pinnedCollectionKeys = pinned,
            orderOf = orders::get,
        )

    @Test
    fun pinnedCollectionsRenderAboveCatalogRowsEvenWhenTheStoredOrderInterleavesThem() {
        val syncedFromAnotherDevice = mapOf(
            "addon:movie:popular" to 0,
            "collection_studios" to 1,
            "addon:series:popular" to 2,
            "collection_kids" to 3,
            "addon:movie:top" to 4,
        )

        assertEquals(
            listOf(
                "collection_studios",
                "collection_kids",
                "addon:movie:popular",
                "addon:series:popular",
                "addon:movie:top",
            ),
            ordered(syncedFromAnotherDevice, pinned = setOf("collection_studios", "collection_kids")),
        )
    }

    @Test
    fun anUnpinnedCollectionKeepsItsStoredPlaceBetweenCatalogRows() {
        val orders = mapOf(
            "addon:movie:popular" to 0,
            "collection_studios" to 1,
            "addon:series:popular" to 2,
            "collection_kids" to 3,
            "addon:movie:top" to 4,
        )

        assertEquals(
            listOf(
                "collection_kids",
                "addon:movie:popular",
                "collection_studios",
                "addon:series:popular",
                "addon:movie:top",
            ),
            ordered(orders, pinned = setOf("collection_kids")),
        )
    }

    @Test
    fun pinnedCollectionsGoFirstAfterAResetThatOrdersCatalogsBeforeCollections() {
        // Reset to defaults clears the stored order: rows fall back to definition order, which puts
        // every collection after every catalog row.
        assertEquals(
            listOf(
                "collection_studios",
                "addon:movie:popular",
                "addon:series:popular",
                "addon:movie:top",
                "collection_kids",
            ),
            ordered(orders = emptyMap(), pinned = setOf("collection_studios")),
        )
    }

    @Test
    fun withNothingPinnedTheStoredOrderIsKept() {
        val orders = mapOf(
            "collection_kids" to 0,
            "addon:movie:top" to 1,
            "addon:movie:popular" to 2,
        )

        assertEquals(
            listOf(
                "collection_kids",
                "addon:movie:top",
                "addon:movie:popular",
                "addon:series:popular",
                "collection_studios",
            ),
            ordered(orders, pinned = emptySet()),
        )
    }
}
