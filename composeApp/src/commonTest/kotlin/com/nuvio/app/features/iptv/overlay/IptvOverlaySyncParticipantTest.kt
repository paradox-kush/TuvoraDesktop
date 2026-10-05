package com.nuvio.app.features.iptv.overlay

import com.nuvio.app.core.contracts.SyncParticipantRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** B115: the website editor's overlay edits (surface `iptv_overlay`) must reach the overlay pull live. */
class IptvOverlaySyncParticipantTest {

    @Test
    fun `overlay participant declares the iptv_overlay realtime surface`() {
        assertTrue("iptv_overlay" in IptvOverlaySyncParticipant.realtimeSurfaces)
    }

    @Test
    fun `the iptv_overlay surface routes to the overlay participant`() {
        val routed = SyncParticipantRegistry.participantsForRealtimeSurface("iptv_overlay", listOf(IptvOverlaySyncParticipant))
        assertEquals(listOf(IptvOverlaySyncParticipant.name), routed.map { it.name })
    }
}
