package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Decision 2026-09-27 (B60 RC3): Desktop ships on the v2 playlist sync. Since the 2026-09-11 backend
 * legacy-write guard, a v1 push to a v2-adopted profile is a silent no-op, so a Desktop kept on v1 lost
 * every playlist add/edit/delete for anyone who also runs a 1.7.x phone or TV — and its v1 pull then
 * overwrote the local edit with the stale server row.
 */
class DesktopPlaylistSyncRolloutTest {

    @Test
    fun `desktop release builds ship with the v2 playlist sync enabled`() {
        assertEquals(PlaylistV2Rollout.ENABLED, PlaylistSyncConfig.buildDefaultRollout)
    }

    @Test
    fun `a fresh desktop profile takes the v2 path in a release build`() {
        assertEquals(
            PlaylistSyncActivation.V2_ACTIVE,
            PlaylistSyncActivationPolicy.activation(PlaylistSyncConfig.buildDefaultRollout, isDebugLocalDev = false, profileHasAdoptedV2 = false),
        )
    }
}
