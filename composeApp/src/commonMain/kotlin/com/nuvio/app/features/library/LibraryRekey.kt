package com.nuvio.app.features.library

import com.nuvio.app.core.contracts.LiveChannelNames

/**
 * B64 — the pure write set of a library re-key ([LibraryRepository.rekeyItems]): the moved items to
 * store once the old ids are removed. One item per (id, type); an item already saved under a moved id
 * stays — re-keying never duplicates a library entry — except that a real name replaces a placeholder
 * one (device pass T3: a favourite saved under the new id as the generic "Live TV" won over the
 * correctly named old one, and Favourites showed "Live TV").
 */
internal object LibraryRekey {

    fun upserts(remaining: Collection<LibraryItem>, moved: List<LibraryItem>): List<LibraryItem> {
        val saved = remaining.associateBy { libraryItemKey(it.id, it.type) }
        val out = LinkedHashMap<String, LibraryItem>()
        for (item in moved) {
            val key = libraryItemKey(item.id, item.type)
            val current = out[key] ?: saved[key]
            when {
                current == null -> out[key] = item
                !LiveChannelNames.isKnown(current.name) && LiveChannelNames.isKnown(item.name) ->
                    out[key] = current.copy(name = item.name, logo = current.logo ?: item.logo, poster = current.poster ?: item.poster)
            }
        }
        return out.values.toList()
    }
}
