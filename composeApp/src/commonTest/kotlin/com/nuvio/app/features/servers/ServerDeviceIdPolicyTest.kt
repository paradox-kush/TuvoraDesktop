package com.nuvio.app.features.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerDeviceIdPolicyTest {
    private val legacy = "nuvio-mobile-abcdefghijklmnopqrstuvwxyz123456"

    @Test
    fun `a stored server device id is never replaced`() {
        assertEquals("tuvora-keep", ServerDeviceIdPolicy.choose(stored = "tuvora-keep", sharedClientId = legacy, hasSignedInServers = true))
        assertEquals(legacy, ServerDeviceIdPolicy.choose(stored = legacy, sharedClientId = legacy, hasSignedInServers = false))
    }

    @Test
    fun `an existing signed-in device keeps its id so its tokens stay valid`() {
        assertEquals(legacy, ServerDeviceIdPolicy.choose(stored = null, sharedClientId = legacy, hasSignedInServers = true))
    }

    @Test
    fun `a new device gets the Tuvora prefix`() {
        val id = ServerDeviceIdPolicy.choose(stored = null, sharedClientId = legacy, hasSignedInServers = false)
        assertTrue(id.startsWith("tuvora-mobile-"), id)
        assertEquals("tuvora-mobile-abcdefghijklmnopqrstuvwxyz123456", id)
    }

    @Test
    fun `an unknown shared id shape still gets a stable tuvora id`() {
        assertEquals("tuvora-mobile-custom-id-0123456789", ServerDeviceIdPolicy.choose(null, "custom-id-0123456789", false))
    }
}
