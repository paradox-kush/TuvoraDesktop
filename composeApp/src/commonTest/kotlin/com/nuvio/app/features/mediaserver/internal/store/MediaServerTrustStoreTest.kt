package com.nuvio.app.features.mediaserver.internal.store

import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerTrust
import com.nuvio.app.features.mediaserver.internal.policy.CertTrustPolicy.FailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaServerTrustStoreTest {
    private class Disk { var json: String? = null }

    private fun trust(disk: Disk = Disk()) = MediaServerTrust(MediaServerTrustStore({ disk.json }, { disk.json = it }, { disk.json = null }))

    @Test
    fun aPinnedCertificateIsAcceptedOnlyWhenSystemValidationFailsAndTheFingerprintMatches() {
        val t = trust()
        assertFalse(t.check("nas:8920", false, FailureKind.SELF_SIGNED, "AA"), "nothing pinned yet: refused, the user is asked")
        val failure = assertNotNull(t.asException("nas:8920"))
        assertEquals("AA", failure.fingerprint)
        t.pin("nas:8920", "AA")
        assertTrue(t.check("nas:8920", false, FailureKind.SELF_SIGNED, "AA"))
        assertFalse(t.check("nas:8920", false, FailureKind.SELF_SIGNED, "BB"), "a different certificate is not the pinned one")
        assertNull(t.asException("other:8920"))
    }

    @Test
    fun aSystemTrustedChainIsAlwaysAcceptedAndNeverRecorded() {
        val t = trust()
        assertTrue(t.check("example.com:443", true, null, "ZZ"))
        assertNull(t.asException("example.com:443"))
    }

    @Test
    fun anExpiredCertificateIsRefusedWithoutOfferingTrust() {
        val t = trust()
        assertFalse(t.check("nas:8920", false, FailureKind.EXPIRED, "AA"))
        assertNull(t.asException("nas:8920"), "TOFU is not offered for an expired certificate")
    }

    @Test
    fun aPublicHostPresentingTheWrongNameIsNotOfferedTrust() {
        val t = trust()
        assertFalse(t.check("jf.example.com:443", false, FailureKind.HOSTNAME_MISMATCH, "AA"))
        assertNull(t.asException("jf.example.com:443"))
        assertFalse(t.check("192.168.1.5:8920", false, FailureKind.HOSTNAME_MISMATCH, "AA"))
        assertTrue(t.asException("192.168.1.5:8920") is MediaServerException.CertificateUntrusted)
    }

    @Test
    fun pinsPersistAreCaseInsensitiveAndUnpinnable() {
        val disk = Disk()
        trust(disk).pin("NAS:8920", "AA")
        val again = trust(disk)
        assertEquals("AA", again.pinnedFingerprint("nas:8920"))
        again.unpin("nas:8920")
        assertNull(trust(disk).pinnedFingerprint("nas:8920"))
        assertNull(disk.json, "the last pin gone: nothing left on disk")
    }

    @Test
    fun clearAllErasesEveryPin() {
        val disk = Disk()
        val t = trust(disk)
        t.pin("a:1", "x"); t.pin("b:2", "y")
        t.clearAll()
        assertNull(t.pinnedFingerprint("a:1"))
        assertNull(disk.json)
    }
}
