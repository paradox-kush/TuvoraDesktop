package com.nuvio.app.features.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerAddressPolicyTest {
    @Test
    fun `an explicit scheme is honoured and tried alone`() {
        assertEquals(listOf("http://10.0.2.2:8096"), ServerAddressPolicy.attempts("http://10.0.2.2:8096"))
        assertEquals(listOf("https://nas.local:8920/jf"), ServerAddressPolicy.attempts(" https://nas.local:8920/jf "))
    }

    @Test
    fun `no scheme tries https first and plain http only after it`() {
        assertEquals(
            listOf("https://10.0.2.2:18443", "http://10.0.2.2:18443"),
            ServerAddressPolicy.attempts("10.0.2.2:18443"),
        )
    }

    @Test
    fun `the Jellyfin and Emby port conventions never bypass https first`() {
        // 8096 is the http default and 8920 the https one; both still start with https, so a typed 8096 asks before any secret goes out in plaintext.
        assertEquals("https://host:8096", ServerAddressPolicy.attempts("host:8096").first())
        assertEquals("https://host:8920", ServerAddressPolicy.attempts("host:8920").first())
        assertEquals("https://host", ServerAddressPolicy.attempts("host").first())
    }

    @Test
    fun `blank input has no attempts`() {
        assertTrue(ServerAddressPolicy.attempts("   ").isEmpty())
    }

    @Test
    fun `only a failure to connect falls back to plain http`() {
        assertTrue(ServerAddressPolicy.fallsBackToPlainHttp(ServerAddressPolicy.Outcome.CONNECT_FAILED))
        assertFalse(ServerAddressPolicy.fallsBackToPlainHttp(ServerAddressPolicy.Outcome.CERTIFICATE), "a certificate error leads to the trust prompt, never to http")
        assertFalse(ServerAddressPolicy.fallsBackToPlainHttp(ServerAddressPolicy.Outcome.SERVER_ANSWERED), "a server that answered over https stays on https")
    }

    @Test
    fun `the errors are classified`() {
        assertEquals(ServerAddressPolicy.Outcome.CERTIFICATE, ServerAddressPolicy.outcomeOf(ServerCertificateException("h:1", "self-signed", "AA", false)))
        assertEquals(ServerAddressPolicy.Outcome.CERTIFICATE, ServerAddressPolicy.outcomeOf(ServerCertificateRefusedException()), "refused but not offerable (expired)")
        assertEquals(ServerAddressPolicy.Outcome.CONNECT_FAILED, ServerAddressPolicy.outcomeOf(ServerConnectException()))
        assertEquals(ServerAddressPolicy.Outcome.SERVER_ANSWERED, ServerAddressPolicy.outcomeOf(ServerException(ServerFailure.UNREACHABLE, "HTTP 503")))
        assertEquals(ServerAddressPolicy.Outcome.SERVER_ANSWERED, ServerAddressPolicy.outcomeOf(ServerException(ServerFailure.NOT_FOUND)))
    }

    @Test
    fun `the plain http address offered to the user is the same host and port`() {
        assertEquals("http://10.0.2.2:18443", ServerAddressPolicy.plainHttpOf("https://10.0.2.2:18443"))
        assertNull(ServerAddressPolicy.plainHttpOf("http://10.0.2.2:18443"), "already plain")
    }
}
