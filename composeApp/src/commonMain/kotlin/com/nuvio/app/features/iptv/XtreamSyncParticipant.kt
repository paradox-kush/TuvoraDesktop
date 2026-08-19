package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.SyncParticipant

internal object XtreamSyncParticipant : SyncParticipant {
    override val name: String = "Xtream accounts"
    override suspend fun pullFromServer(profileId: Int) {
        XtreamAccountSyncService.pullFromServer(profileId)
    }
}
