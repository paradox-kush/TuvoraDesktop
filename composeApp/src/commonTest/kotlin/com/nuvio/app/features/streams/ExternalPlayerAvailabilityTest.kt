package com.nuvio.app.features.streams

import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerPlaybackTarget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalPlayerAvailabilityTest {
    private val addon = StreamItem(name = "1080p", url = "https://cdn.example/v.mkv", addonName = "A", addonId = "a")
    private val server = StreamItem(
        name = "Movie",
        addonName = "Jellyfin · Den",
        addonId = "server:c1",
        serverTarget = ServerPlaybackTarget(ServerItemRef("c1", "item"), mediaSourceId = null),
    )

    @Test
    fun `server streams never leave the app`() {
        assertFalse(ExternalPlayerAvailability.canHandOff(server), "listed server stream")
        assertFalse(ExternalPlayerAvailability.canHandOff(server.copy(url = "https://s.example/Videos/1/stream?PlaySessionId=x")), "prepared server stream")
        assertFalse(ExternalPlayerAvailability.canHandOffProvider("server:c1"), "playing server source")
    }

    @Test
    fun `ordinary streams can be handed off`() {
        assertTrue(ExternalPlayerAvailability.canHandOff(addon), "add-on stream")
        assertTrue(ExternalPlayerAvailability.canHandOffProvider("a"), "add-on provider")
        assertTrue(ExternalPlayerAvailability.canHandOffProvider(null), "unknown provider")
        assertFalse(ExternalPlayerAvailability.canHandOff(null), "nothing to hand off")
    }

    @Test
    fun `routing honours the setting force flags and the server rule`() {
        assertTrue(ExternalPlayerAvailability.routeExternally(addon, externalPlaybackReady = true), "setting on")
        assertTrue(ExternalPlayerAvailability.routeExternally(addon, externalPlaybackReady = false, forceExternal = true), "forced external")
        assertFalse(ExternalPlayerAvailability.routeExternally(addon, externalPlaybackReady = true, forceInternal = true), "forced internal")
        assertFalse(ExternalPlayerAvailability.routeExternally(addon, externalPlaybackReady = false), "setting off")
        assertFalse(ExternalPlayerAvailability.routeExternally(server, externalPlaybackReady = true), "setting on, server stream")
        assertFalse(ExternalPlayerAvailability.routeExternally(server, externalPlaybackReady = false, forceExternal = true), "forced external, server stream")
    }
}
