package com.nuvio.app.features.servers

import com.nuvio.app.features.servers.RelayRedirectPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayRedirectPolicyTest {
    private val authority = "nas.local:8920"
    private fun decide(location: String?, status: Int = 302) = RelayRedirectPolicy.decide(status, location, authority)

    @Test
    fun `a redirect to a public https host is passed to the player`() {
        assertEquals(Decision.PassThrough("https://cdn.debrid.example/dl/abc?t=1"), decide("https://cdn.debrid.example/dl/abc?t=1"), "debrid cdn")
        assertEquals(Decision.PassThrough("https://203.0.113.9.example.com/x"), decide("https://203.0.113.9.example.com/x"), "ip-looking label of a real domain")
        assertEquals(Decision.PassThrough("https://93.184.216.34/x"), decide("https://93.184.216.34/x"), "public ipv4 literal")
        assertEquals(Decision.PassThrough("https://[2606:4700::1111]/x"), decide("https://[2606:4700::1111]/x"), "public ipv6 literal")
        listOf(301, 302, 303, 307, 308).forEach { assertEquals(Decision.PassThrough("https://cdn.example.com/x"), decide("https://cdn.example.com/x", it), "status $it") }
    }

    @Test
    fun `a redirect back to the pinned server is served through the relay`() {
        assertEquals(Decision.ViaRelay("/Videos/1/stream?x=1"), decide("https://nas.local:8920/Videos/1/stream?x=1"), "absolute")
        assertEquals(Decision.ViaRelay("/a/b"), decide("/a/b"), "root relative")
        assertEquals(Decision.ViaRelay("/a"), decide("HTTPS://NAS.LOCAL:8920/a"), "case-insensitive")
    }

    @Test
    fun `loopback private and link-local targets are refused`() {
        listOf(
            "https://localhost/x", "https://foo.localhost/x", "https://127.0.0.1/x", "https://127.1/x", "https://0x7f.0.0.1/x",
            "https://2130706433/x", "https://017700000001/x", "https://0.0.0.0/x", "https://10.0.0.5/x", "https://172.16.0.1/x",
            "https://172.31.255.255/x", "https://192.168.1.1/x", "https://169.254.169.254/x", "https://100.64.0.1/x",
            "https://224.0.0.1/x", "https://255.255.255.255/x", "https://[::1]/x", "https://[::]/x", "https://[::ffff:127.0.0.1]/x",
            "https://[::ffff:7f00:1]/x", "https://[fd00::1]/x", "https://[fe80::1]/x", "https://[2002:7f00:1::]/x", "https://[64:ff9b::7f00:1]/x",
        ).forEach { assertEquals(Decision.Refuse, decide(it), "refused: $it") }
    }

    @Test
    fun `names that resolve to private space are refused`() {
        listOf(
            "https://nas/x", "https://printer.local/x", "https://router.lan/x", "https://box.home.arpa/x", "https://svc.internal/x",
            "https://127.0.0.1.nip.io/x", "https://10-0-0-1.sslip.io/x", "https://app.192.168.1.5.nip.io/x", "https://anything.localtest.me/x",
        ).forEach { assertEquals(Decision.Refuse, decide(it), "refused: $it") }
        assertEquals(Decision.PassThrough("https://8.8.8.8.nip.io/x"), decide("https://8.8.8.8.nip.io/x"), "a public embedded address is not private")
    }

    @Test
    fun `downgrades and malformed locations are refused`() {
        listOf(
            "http://cdn.example.com/x", "//cdn.example.com/x", "ftp://cdn.example.com/x", "https://user:pw@cdn.example.com/x",
            "https://cdn.example.com/x y", "javascript:alert(1)", "https:///x", "", "not a url",
        ).forEach { assertEquals(Decision.Refuse, decide(it), "refused: $it") }
        assertEquals(Decision.Refuse, decide(null), "no location")
        assertEquals(Decision.Refuse, decide("https://cdn.example.com/x", status = 200), "not a redirect")
        assertEquals(Decision.Refuse, decide("https://cdn.example.com/x", status = 304), "not modified is not a redirect")
    }

    @Test
    fun `host classification helper`() {
        assertTrue(RelayRedirectPolicy.isPublicHost("cdn.example.com"), "public name")
        assertFalse(RelayRedirectPolicy.isPublicHost(""), "empty")
        assertFalse(RelayRedirectPolicy.isPublicHost("fe80::1%eth0"), "zone id")
        assertTrue(RelayRedirectPolicy.isRedirect(307) && !RelayRedirectPolicy.isRedirect(300) && !RelayRedirectPolicy.isRedirect(304), "statuses")
    }
}
