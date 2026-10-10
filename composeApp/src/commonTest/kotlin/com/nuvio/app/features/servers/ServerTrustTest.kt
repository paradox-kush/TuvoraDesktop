package com.nuvio.app.features.servers

import com.nuvio.app.features.servers.ServerCertTrustPolicy.FailureKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTrustTest {
    private class Disk { var json: String? = null }

    private fun trust(disk: Disk = Disk()) = ServerTrust(ServerPinStore({ disk.json }, { disk.json = it }, { disk.json = null }))

    @Test
    fun `an untrusted certificate is refused until pinned then accepted`() {
        val trust = trust()
        assertFalse(trust.check("nas.local:8920", false, FailureKind.SELF_SIGNED, "AA"), "first sight is refused")
        val failure = assertNotNull(trust.certificateException("nas.local:8920"), "the refusal is kept for the UI")
        assertEquals("AA", failure.fingerprint, "fingerprint")
        assertFalse(failure.changed, "not a change")

        trust.pin("nas.local:8920", "AA")
        assertTrue(trust.check("nas.local:8920", false, FailureKind.SELF_SIGNED, "AA"), "pinned matches")
    }

    @Test
    fun `a pin is scoped to its own host and port`() {
        val trust = trust()
        trust.pin("nas.local:8920", "AA")
        assertFalse(trust.check("other.local:8920", false, FailureKind.SELF_SIGNED, "AA"), "other host")
        assertFalse(trust.check("nas.local:8921", false, FailureKind.SELF_SIGNED, "AA"), "other port")
    }

    @Test
    fun `a changed fingerprint hard fails as a change`() {
        val trust = trust()
        trust.pin("nas.local:8920", "AA")
        assertFalse(trust.check("nas.local:8920", false, FailureKind.SELF_SIGNED, "BB"), "changed is refused")
        val failure = assertNotNull(trust.certificateException("nas.local:8920"), "recorded")
        assertTrue(failure.changed, "reported as changed")
        assertEquals(ServerFailure.CERTIFICATE_CHANGED, failure.failure, "failure kind")
        assertEquals("BB", failure.fingerprint, "the new fingerprint")
    }

    @Test
    fun `an expired certificate is never offered for trust`() {
        val trust = trust()
        assertFalse(trust.check("nas.local:8920", false, FailureKind.EXPIRED, "AA"), "refused")
        assertNull(trust.certificateException("nas.local:8920"), "no trust prompt for expired")
    }

    @Test
    fun `pins persist and unpin removes them`() {
        val disk = Disk()
        trust(disk).pin("nas.local:8920", "AA")
        val reloaded = trust(disk)
        assertEquals("AA", reloaded.pinnedFingerprint("nas.local:8920"), "persisted on this device")
        reloaded.unpin("nas.local:8920")
        assertNull(trust(disk).pinnedFingerprint("nas.local:8920"), "removed")
    }

    @Test
    fun `the pinned fingerprint is never part of the sync payload`() {
        val trust = trust()
        trust.pin("fake.example:443", "DEADBEEFFINGERPRINT")
        val connection = ServerConnection(
            id = "c1", providerId = "fake", name = "Box", address = "https://fake.example",
            remoteServerId = "s1", remoteUserId = "u1", userName = "viewer", credentialRef = "k1",
        )
        val payload = listOf(connection.toSynced()).toSyncPayload()
        assertFalse(payload.toString().contains("DEADBEEF"), "no fingerprint in the pushed json")
        assertEquals(
            setOf(
                "id", "provider_id", "name", "address", "remote_server_id", "remote_user_id",
                "user_name", "token", "libraries", "enabled", "use_catalog_metadata", "import_watch_state",
            ),
            payload.jsonArray.single().jsonObject.keys,
            "the wire shape has no trust field",
        )
    }
}
