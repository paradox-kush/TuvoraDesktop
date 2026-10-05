package com.nuvio.app.features.iptv.overlay

import com.nuvio.app.core.contracts.SyncParticipant

/**
 * Fork surface: the IPTV personalization overlay (hide / pin / reorder / groups) pulls its edits on
 * profile sync. Registered at the composition root ([com.nuvio.app.FeatureWiring]) so the shared
 * SyncManager never names this feature — it rides the SyncParticipant loop alongside Xtream accounts
 * and Radar follows, keeping upstream's sync pipeline fork-clean (arch R2b).
 */
internal object IptvOverlaySyncParticipant : SyncParticipant {
    override val name: String = "IPTV overlay"

    /** Emitted by `sync_push_iptv_overlay` (website editor + other devices) — B115. */
    override val realtimeSurfaces: Set<String> = setOf("iptv_overlay")

    override suspend fun pullFromServer(profileId: Int) {
        IptvOverlayRepository.pullForProfile(profileId)
    }
}
