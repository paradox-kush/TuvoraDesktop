package com.nuvio.app.features.radar

import com.nuvio.app.core.contracts.SyncParticipant

internal object RadarSyncParticipant : SyncParticipant {
    override val name: String = "Radar follows"

    /** B03: emitted by sync_push_radar (website Sports tab, other devices). */
    override val realtimeSurfaces: Set<String> = setOf("radar")
    override suspend fun pullFromServer(profileId: Int) {
        RadarSyncService.pullFromServer(profileId)
    }
}
