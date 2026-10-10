package com.nuvio.app.features.servers

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Media-server access tokens are device-local: never pushed, never imported (decision 2026-10-10). */
class ServerTokenSyncTest {
    @AfterTest
    fun tearDown() {
        ServerRepository.remove("other") // the server that "arrived from another device"
        removeFakeServer()
    }

    private val connection = ServerConnection(
        id = "c1", providerId = "fake", name = "Box", address = "https://fake.example",
        remoteServerId = "s1", remoteUserId = "u1", userName = "viewer", credentialRef = "k1",
    )

    @Test
    fun `pushed json carries no token`() {
        val json = listOf(connection.toSynced()).toSyncPayload()
        val row = json.jsonArray.single().jsonObject
        assertEquals("", row.getValue("token").jsonPrimitive.content, "token is empty on the wire")
        assertEquals("viewer", row.getValue("user_name").jsonPrimitive.content, "other fields still sync")
        assertEquals("https://fake.example", row.getValue("address").jsonPrimitive.content, "address still syncs")
    }

    @Test
    fun `an empty or foreign remote token keeps the local one`() {
        assertEquals("local", ServerTokenPolicy.afterPull(local = "local", remote = ""), "empty remote")
        assertEquals("local", ServerTokenPolicy.afterPull(local = "local", remote = "other"), "foreign remote")
        assertNull(ServerTokenPolicy.afterPull(local = null, remote = "other"), "remote tokens are never imported")
    }

    @Test
    fun `no local token means sign in on this device`() {
        assertTrue(ServerTokenPolicy.needsSignIn(null), "missing")
        assertTrue(ServerTokenPolicy.needsSignIn(" "), "blank")
        assertFalse(ServerTokenPolicy.needsSignIn("t"), "present")
    }

    @Test
    fun `a server pulled from another device lands in the sign in again state`() {
        installFakeServer()
        val snapshot = assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId))
        val arrived = snapshot.servers.single().copy(id = "other", remoteServerId = "server-2")
        val keys = setOf(snapshot.servers.single().key, arrived.key)
        assertTrue(ServerRepository.applySync(snapshot, snapshot.servers + arrived, keys), "applied")

        val added = ServerRepository.uiState.value.connections.single { it.remoteServerId == "server-2" }
        assertEquals(ServerFailure.AUTH_REQUIRED, ServerRepository.uiState.value.failures[added.id], "needs a sign in here")
        assertNull(ServerRepository.session(added.id), "no session without a local token")
        assertEquals("token", ServerRepository.session("cfake")?.token, "the signed-in server keeps its token")
    }
}
