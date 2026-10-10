package com.nuvio.app.features.servers

import com.nuvio.app.features.servers.ServerCertTrustPolicy.FailureKind
import com.nuvio.app.features.servers.ServerCertTrustPolicy.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerCertTrustPolicyTest {
    private fun decide(
        trusted: Boolean = false,
        failure: FailureKind? = FailureKind.SELF_SIGNED,
        presented: String = "AA",
        pinned: String? = null,
        host: String = "192.168.1.5",
    ) = ServerCertTrustPolicy.decide(trusted, failure, presented, pinned, host)

    @Test
    fun `a system trusted chain is accepted and never pinned`() {
        assertEquals(Verdict.Accept, decide(trusted = true, failure = null), "trusted")
        assertEquals(Verdict.Accept, decide(trusted = true, failure = null, pinned = "OLD", presented = "NEW"), "stale pin loses to a public chain")
    }

    @Test
    fun `first seen certificate offers trust on first use`() {
        assertEquals(Verdict.AskTrustNew("AA"), decide(), "self signed")
        assertEquals(Verdict.AskTrustNew("AA"), decide(failure = FailureKind.UNTRUSTED_CHAIN, host = "jf.example.com"), "private ca")
    }

    @Test
    fun `a matching pin is accepted silently`() {
        assertEquals(Verdict.Accept, decide(pinned = "AA", presented = "AA"), "match")
    }

    @Test
    fun `a different certificate than the pinned one is reported as changed`() {
        assertEquals(Verdict.AskCertificateChanged("AA", "BB"), decide(pinned = "AA", presented = "BB"), "mismatch")
    }

    @Test
    fun `expired or unknown failures are never papered over`() {
        assertEquals(Verdict.Reject(FailureKind.EXPIRED), decide(failure = FailureKind.EXPIRED, pinned = "AA", presented = "AA"), "expired")
        assertEquals(Verdict.Reject(FailureKind.OTHER), decide(failure = FailureKind.OTHER), "other")
        assertEquals(Verdict.Reject(FailureKind.OTHER), decide(failure = null), "unknown is other")
    }

    @Test
    fun `a hostname mismatch is only forgiven for local or ip hosts`() {
        assertTrue(ServerCertTrustPolicy.canOfferTrust(FailureKind.HOSTNAME_MISMATCH, "192.168.1.5"), "lan ip")
        assertTrue(ServerCertTrustPolicy.canOfferTrust(FailureKind.HOSTNAME_MISMATCH, "nas.local"), "mdns")
        assertTrue(ServerCertTrustPolicy.canOfferTrust(FailureKind.HOSTNAME_MISMATCH, "8.8.8.8"), "ip literal")
        assertFalse(ServerCertTrustPolicy.canOfferTrust(FailureKind.HOSTNAME_MISMATCH, "jf.example.com"), "public name")
        assertEquals(Verdict.Reject(FailureKind.HOSTNAME_MISMATCH), decide(failure = FailureKind.HOSTNAME_MISMATCH, host = "jf.example.com"), "reject")
    }
}
