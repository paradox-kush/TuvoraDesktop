package com.nuvio.app.features.catalog

import com.nuvio.app.features.library.LibrarySortOption
import kotlinx.serialization.Serializable

sealed interface CatalogTarget {
    val contentType: String
    val supportsPagination: Boolean

    data class Addon(
        val manifestUrl: String,
        override val contentType: String,
        val catalogId: String,
        val genre: String? = null,
        override val supportsPagination: Boolean = false,
    ) : CatalogTarget

    data class Library(
        override val contentType: String,
        val sectionType: String,
        val sortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    ) : CatalogTarget {
        override val supportsPagination: Boolean = false
    }

    data class CollectionSource(
        val collectionId: String,
        val folderId: String,
        val sourceKey: String,
        override val contentType: String,
        override val supportsPagination: Boolean = false,
    ) : CatalogTarget

    /**
     * "See all" for a row an own source contributed to Home (a media server's list). [sourceKey]
     * identifies the contributing source (stable across re-login - source identity, never a user id),
     * [listId] which of its lists. Served by the owning
     * [HomeSectionContributor][com.nuvio.app.core.contracts.HomeSectionContributor].
     */
    data class Source(
        val sourceKey: String,
        val listId: String,
        override val contentType: String,
        override val supportsPagination: Boolean = false,
    ) : CatalogTarget
}

@Serializable
enum class CatalogTargetKind {
    ADDON,
    LIBRARY,
    COLLECTION_SOURCE,
    SOURCE,
}
