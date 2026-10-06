package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.contracts.IptvContentClassifier
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntryStore

/**
 * The media-server answers of the neutral content classifier (design 5.7): a removed server's cards read as
 * ORPHANED so Library / Continue Watching clear them, and a saved `ms:` id borrows its poster from the
 * registry. Nothing here is "live" or Xtream.
 */
internal class MediaServerClassifier(private val store: MediaServerEntryStore) : IptvContentClassifier {
    override fun isLiveId(id: String): Boolean = false

    /** The id belongs to a media-server entry that no longer exists on this profile. */
    override fun isOrphaned(id: String): Boolean {
        val parsed = MediaServerIds.parse(id) ?: return false
        // the entry may have re-logged-in as another user: it is orphaned only when no entry names this machine at all
        val entries = store.current()
        return entries.none { it.type == parsed.type && it.machineId == parsed.machineId }
    }

    override fun isXtreamId(id: String): Boolean = false

    override fun posterFor(id: String): String? = MediaServerItemRegistry.get(id)?.poster

    override fun isXtreamStreamGroup(addonId: String): Boolean = false
}
