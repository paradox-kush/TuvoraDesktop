package com.nuvio.app.core.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B115: a website overlay edit emits a `sync_invalidations` row with surface `iptv_overlay`. The shared
 * SyncManager doesn't name fork features, so the surface must route to the participant that declares it —
 * before this, the surface fell through the `when` and the edit only arrived when Live TV was reopened.
 */
class SyncParticipantRealtimeRoutingTest {

    private class Fake(override val name: String, override val realtimeSurfaces: Set<String>) : SyncParticipant {
        val pulls = mutableListOf<Int>()
        override suspend fun pullFromServer(profileId: Int) { pulls += profileId }
    }

    @Test
    fun `a fork surface routes only to the participants that declare it`() {
        val overlay = Fake("overlay", setOf("iptv_overlay"))
        val accounts = Fake("accounts", emptySet())
        val routed = SyncParticipantRegistry.participantsForRealtimeSurface("iptv_overlay", listOf(accounts, overlay))
        assertEquals(listOf("overlay"), routed.map { it.name })
    }

    @Test
    fun `an unknown surface routes nowhere`() {
        val overlay = Fake("overlay", setOf("iptv_overlay"))
        assertTrue(SyncParticipantRegistry.participantsForRealtimeSurface("radar", listOf(overlay)).isEmpty())
    }

    @Test
    fun `participants declare no realtime surfaces by default`() {
        val plain = object : SyncParticipant {
            override val name = "plain"
            override suspend fun pullFromServer(profileId: Int) = Unit
        }
        assertTrue(plain.realtimeSurfaces.isEmpty())
    }
}
