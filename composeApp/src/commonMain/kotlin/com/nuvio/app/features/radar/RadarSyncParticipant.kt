package com.nuvio.app.features.radar

import com.nuvio.app.core.contracts.SyncParticipant

internal object RadarSyncParticipant : SyncParticipant {
    override val name: String = "Radar follows"
    override suspend fun pullFromServer(profileId: Int) {
        RadarSyncService.pullFromServer(profileId)
    }
}
