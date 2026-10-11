package com.nuvio.app.features.servers

import com.nuvio.app.features.servers.PinnedMediaPolicy.Route
import com.nuvio.app.features.servers.RelayRequestPolicy.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PinnedMediaPolicyTest {
    private val pins = mapOf("nas.local:8920" to "AA")
    private fun route(url: String?) = PinnedMediaPolicy.route(url) { pins[it] }

    @Test
    fun `a pinned https server is routed through the pin`() {
        assertEquals(Route.Pinned("nas.local:8920"), route("https://nas.local:8920/Items/1/Images/Primary?tag=x"), "pinned host")
    }

    @Test
    fun `everything else keeps the normal path`() {
        assertEquals(Route.Direct, route("https://jellyfin.example.com/Items/1"), "ca signed server, no pin")
        assertEquals(Route.Direct, route("https://nas.local:8921/x"), "same host other port")
        assertEquals(Route.Direct, route("http://nas.local:8920/x"), "plain http is never pinned")
        assertEquals(Route.Direct, route("http://127.0.0.1:5000/token/x"), "the relay itself")
        assertEquals(Route.Direct, route(null), "null")
        assertEquals(Route.Direct, route("not a url"), "garbage")
    }

    @Test
    fun `a default https port is matched`() {
        val pinned = mapOf("nas.local:443" to "AA")
        assertEquals(Route.Pinned("nas.local:443"), PinnedMediaPolicy.route("https://nas.local/x") { pinned[it] }, "443 filled in")
    }

    private val token = "t0k3n"
    private fun parse(
        line: String = "GET /$token/Videos/1/stream?static=true HTTP/1.1",
        headers: List<String> = listOf("Host: 127.0.0.1:5000", "Range: bytes=0-99"),
    ) = RelayRequestPolicy.parse(line, headers, token, 5000)

    @Test
    fun `a good request is accepted with an allow listed header set`() {
        val ok = parse(headers = listOf("Host: 127.0.0.1:5000", "Range: bytes=0-99", "Cookie: a=b", "Authorization: Bearer x", "User-Agent: mpv"))
        assertEquals(
            Result.Ok("GET", "/Videos/1/stream?static=true", mapOf("Range" to "bytes=0-99", "User-Agent" to "mpv")),
            ok,
            "cookie and authorization are not forwarded",
        )
    }

    @Test
    fun `the token is required and anything else is not found`() {
        assertEquals(Result.Reject(404), parse(line = "GET /wrong/Videos/1 HTTP/1.1"), "wrong token")
        assertEquals(Result.Reject(404), parse(line = "GET /Videos/1 HTTP/1.1"), "no token")
        assertEquals(Result.Reject(404), parse(line = "GET /${token}x/Videos/1 HTTP/1.1"), "token prefix only")
    }

    @Test
    fun `only get and head are served and never as a proxy`() {
        assertEquals(Result.Reject(405), parse(line = "POST /$token/x HTTP/1.1"), "post")
        assertEquals(Result.Reject(405), parse(line = "CONNECT evil.example:443 HTTP/1.1"), "connect")
        assertEquals(Result.Reject(400), parse(line = "GET https://evil.example/$token/x HTTP/1.1"), "absolute form")
        assertEquals(Result.Reject(400), parse(line = "GET /$token/x\u0000y HTTP/1.1"), "control character")
        assertEquals(Result.Reject(400), parse(line = "GET /$token/x"), "no version")
        assertEquals(Result.Ok("HEAD", "/x", mapOf("Accept" to "*/*")), parse(line = "HEAD /$token/x HTTP/1.1", headers = listOf("Host: 127.0.0.1:5000", "Accept: */*")), "head")
    }

    @Test
    fun `the request must address the relay itself and carry no body`() {
        assertEquals(Result.Reject(403), parse(headers = listOf("Host: evil.example")), "rebinding host")
        assertEquals(Result.Reject(403), parse(headers = emptyList()), "missing host")
        assertEquals(Result.Reject(400), parse(headers = listOf("Host: 127.0.0.1:5000", "Content-Length: 5")), "body")
        assertEquals(Result.Reject(400), parse(headers = listOf("Host: 127.0.0.1:5000", "Transfer-Encoding: chunked")), "chunked")
        assertEquals(Result.Reject(400), parse(headers = listOf("garbage")), "malformed header")
    }

    @Test
    fun `responses pass a fixed allow list`() {
        val out = RelayRequestPolicy.responseHeaders(
            mapOf("Content-Type" to listOf("video/mp4"), "Set-Cookie" to listOf("s=1"), "content-range" to listOf("bytes 0-9/100"), "X-Secret" to listOf("y")),
        )
        assertEquals(listOf("Content-Type" to "video/mp4", "Content-Range" to "bytes 0-9/100"), out, "no cookies, no custom headers")
    }

    @Test
    fun `a redirect is only followed on the same authority`() {
        assertEquals("/a/b?c=1", RelayRequestPolicy.sameAuthorityLocation("/a/b?c=1", "nas.local:8920"), "relative")
        assertEquals("/a/b", RelayRequestPolicy.sameAuthorityLocation("https://nas.local:8920/a/b", "nas.local:8920"), "same authority")
        assertNull(RelayRequestPolicy.sameAuthorityLocation("https://evil.example/a", "nas.local:8920"), "foreign host")
        assertNull(RelayRequestPolicy.sameAuthorityLocation("//evil.example/a", "nas.local:8920"), "scheme relative")
        assertNull(RelayRequestPolicy.sameAuthorityLocation("http://nas.local:8920/a", "nas.local:8920"), "downgrade")
        assertNull(RelayRequestPolicy.sameAuthorityLocation(null, "nas.local:8920"), "none")
    }

    // Desktop's libmpv does not verify TLS by default (bundled build: tls-verify=no), and a media-server stream carries its
    // credential in the URL: on Desktop every https media-server host is read through the verifying relay, pinned or CA-signed.
    @Test
    fun `desktop playback relays every https media server host, not only pinned ones`() {
        val servers = setOf("jelly.example.com:443", "nas.local:8920")
        val pins: (String) -> String? = { if (it == "nas.local:8920") "FP" else null }
        val isServer: (String) -> Boolean = { it in servers }
        assertEquals(PinnedMediaPolicy.Route.Pinned("nas.local:8920"), PinnedMediaPolicy.playbackRoute("https://nas.local:8920/Videos/1/stream", pins, isServer), "pinned")
        assertEquals(PinnedMediaPolicy.Route.Pinned("jelly.example.com:443"), PinnedMediaPolicy.playbackRoute("https://jelly.example.com/Videos/1/stream?api_key=k", pins, isServer), "CA-signed server")
    }

    @Test
    fun `desktop playback leaves everything that is not a media server host direct`() {
        val isServer: (String) -> Boolean = { it == "jelly.example.com:443" }
        val none: (String) -> String? = { null }
        assertEquals(PinnedMediaPolicy.Route.Direct, PinnedMediaPolicy.playbackRoute("https://iptv.example.net:443/live/u/p/1.ts", none, isServer), "IPTV / add-on / debrid host")
        assertEquals(PinnedMediaPolicy.Route.Direct, PinnedMediaPolicy.playbackRoute("http://jelly.example.com:443/x", none, isServer), "plain http")
        assertEquals(PinnedMediaPolicy.Route.Direct, PinnedMediaPolicy.playbackRoute("https://jelly.example.com:8443/x", none, isServer), "another port")
        assertEquals(PinnedMediaPolicy.Route.Direct, PinnedMediaPolicy.playbackRoute(null, none, isServer))
        assertEquals(PinnedMediaPolicy.Route.Direct, PinnedMediaPolicy.playbackRoute("http://127.0.0.1:1234/token/x", none) { true }, "the relay itself")
    }
}
