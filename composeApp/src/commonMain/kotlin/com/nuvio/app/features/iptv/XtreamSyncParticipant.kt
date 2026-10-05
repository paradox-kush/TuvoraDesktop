package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.SyncParticipant

internal object XtreamSyncParticipant : SyncParticipant {
    override val name: String = "Xtream accounts"

    /** B03: emitted when a profile's playlist revision advances (web editor, provider, other devices). */
    override val realtimeSurfaces: Set<String> = setOf("iptv_playlists")
    override suspend fun pullFromServer(profileId: Int) {
        XtreamAccountSyncService.pullFromServer(profileId)
        // The one extra read this feature adds to a playlist pull: which of the pulled playlists a
        // provider manages. Delta-shaped and never on a timer — see ManagedInfoRefreshPolicy.
        ManagedInfoRefresher.afterPlaylistPull(profileId)
    }
}
