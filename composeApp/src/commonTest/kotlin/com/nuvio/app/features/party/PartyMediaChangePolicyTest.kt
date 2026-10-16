package com.nuvio.app.features.party

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyMediaChangePolicyTest {
    private val movie = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie", title = "One")
    private val other = PartyMedia(type = "movie", metaId = "tt2", metaType = "movie", title = "Two")

    private fun party(memberId: String, hostOnly: Boolean = false) = ActiveParty(
        id = "p",
        code = "ABCDEF",
        memberId = memberId,
        hostMemberId = "host",
        members = emptyList(),
        presence = emptyMap(),
        media = movie,
        maxMembers = 3,
        connected = true,
        hostOnly = hostOnly,
    )

    @Test
    fun `the host can switch the party to something else`() {
        assertTrue(PartyMediaChangePolicy.canPublish(party("host"), other))
        assertTrue(PartyMediaChangePolicy.canPublish(party("host", hostOnly = true), other))
    }

    @Test
    fun `a guest cannot change what is playing even when everyone may control playback`() {
        assertFalse(PartyMediaChangePolicy.canPublish(party("guest", hostOnly = false), other))
        assertFalse(PartyMediaChangePolicy.canPublish(party("guest", hostOnly = true), other))
    }

    @Test
    fun `publishing the media already playing is a no-op`() {
        assertFalse(PartyMediaChangePolicy.canPublish(party("host"), movie))
    }
}
