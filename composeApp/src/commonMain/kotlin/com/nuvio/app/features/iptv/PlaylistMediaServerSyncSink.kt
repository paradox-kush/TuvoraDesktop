package com.nuvio.app.features.iptv

import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.api.MediaServerPendingOp
import com.nuvio.app.features.mediaserver.api.MediaServerPendingOps.recordAdd
import com.nuvio.app.features.mediaserver.api.MediaServerPendingOps.recordDelete
import com.nuvio.app.features.mediaserver.api.MediaServerPendingOps.recordUpdate
import com.nuvio.app.features.mediaserver.api.MediaServerSyncSink

/**
 * Where a LOCAL media-server entry change enters the playlist-sync engine (Wave 3, design 5.3): appended to the
 * durable per-profile pending log - the same state blob, rules and activation gate as a playlist edit (only
 * recorded while the v2 path is active or adopted; the legacy v1 path never carries server entries) - then a
 * debounced push is requested. The engine reconciles the intent onto the server's rows and acknowledges it only
 * after the commit.
 */
internal object PlaylistMediaServerSyncSink : MediaServerSyncSink {
    override fun recordAdd(profileId: Int, entry: MediaServerEntry) = record(profileId) { it.recordAdd(entry) }

    override fun recordUpdate(profileId: Int, entry: MediaServerEntry, base: MediaServerEntry?) =
        record(profileId) { it.recordUpdate(entry, base) }

    override fun recordDelete(profileId: Int, key: String) = record(profileId) { it.recordDelete(key) }

    private fun record(profileId: Int, transform: (List<MediaServerPendingOp>) -> List<MediaServerPendingOp>) {
        val state = decodePlaylistSyncState(XtreamAccountStorage.loadPlaylistSyncStateJson(profileId))
        val adopted = state.revision > 0 || state.pending.isNotEmpty() || state.mediaServerPending.isNotEmpty()
        if (!PlaylistSyncConfig.recordsPending(adopted)) return
        XtreamAccountStorage.savePlaylistSyncStateJson(
            profileId,
            encodePlaylistSyncState(state.copy(mediaServerPending = transform(state.mediaServerPending))),
        )
        XtreamAccountSyncService.triggerPush()
    }
}
