package com.nuvio.app.features.servers

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toUri
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Artwork and playback of a PINNED self-signed media server against a REAL local HTTPS server (keytool certificate, real
 * TLS, real sockets, real Range requests): the Coil fetcher and the loopback relay read through the pinned client; without
 * the pin, or after the certificate changes, they fail; hosts that were never pinned keep the normal path. An ffmpeg
 * reader (libmpv's network stack) stands in for the player.
 */
class PinnedMediaE2ETest {
    private val video = ByteArray(3 * 1024 * 1024) { (it * 31 + it / 251).toByte() }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5, 0xFF.toByte(), 0xD9.toByte())

    private lateinit var tls: Tls
    private lateinit var trust: ServerTrust
    private lateinit var http: TrustedServerHttp
    private lateinit var relay: PinnedMediaRelay
    private val upstreamHeaders = mutableMapOf<String, List<String>>()
    private val slowGate = CountDownLatch(1)
    private val subtitle = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".encodeToByteArray()
    @Volatile private var realMp4: ByteArray? = null

    private class Disk { var json: String? = null }

    private class Tls(val server: HttpsServer, val port: Int, val fingerprint: String) {
        fun stop() = server.stop(0)
    }

    private val client: HttpClient = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        tls = startServer(port = 0)
        val disk = Disk()
        trust = ServerTrust(ServerPinStore({ disk.json }, { disk.json = it }, { disk.json = null }))
        http = TrustedServerHttp(trust)
        relay = PinnedMediaRelay(http)
    }

    @AfterTest
    fun tearDown() {
        tls.stop()
        slowGate.countDown()
    }

    private val authority get() = "127.0.0.1:${tls.port}"
    private fun pin() = trust.pin(authority, tls.fingerprint)
    private fun source(path: String) = "https://$authority$path"

    private fun get(url: String, vararg headers: Pair<String, String>, method: String = "GET"): HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun open(path: String): Pair<RelaySession, String> = runBlocking {
        val session = relay.open(authority)
        session to assertNotNull(session.rewrite(source(path)))
    }

    // ---------------------------------------------------------------- video through the relay

    @Test
    fun `a pinned self-signed server plays through the relay with real range requests`() {
        pin()
        val (session, url) = open("/video.mp4?static=true&api_key=K")
        try {
            assertTrue(url.startsWith("http://127.0.0.1:"), "the player gets a loopback http url")
            assertFalse(url.contains(authority), "the server address is not handed to the player")
            val range = get(url, "Range" to "bytes=1000-1999")
            assertEquals(206, range.statusCode())
            assertEquals("bytes 1000-1999/${video.size}", range.headers().firstValue("Content-Range").orElse(null))
            assertContentEquals(video.copyOfRange(1000, 2000), range.body())
            val tail = get(url, "Range" to "bytes=${video.size - 10}-")
            assertContentEquals(video.copyOfRange(video.size - 10, video.size), tail.body())
            val whole = get(url)
            assertEquals(200, whole.statusCode())
            assertEquals("video/mp4", whole.headers().firstValue("Content-Type").orElse(null))
            assertContentEquals(video, whole.body())
            val head = get(url, method = "HEAD")
            assertEquals(200, head.statusCode())
            assertEquals(video.size.toString(), head.headers().firstValue("Content-Length").orElse(null))
            assertEquals(0, head.body().size)
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `the body is streamed, not buffered whole`() {
        pin()
        val (session, url) = open("/slow")
        try {
            val response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            // The server is still holding the rest of the body: the first chunk must already be readable.
            val first = ByteArray(4)
            assertEquals(4, response.body().readNBytes(first, 0, 4))
            assertContentEquals("head".encodeToByteArray(), first)
            slowGate.countDown()
            assertEquals("-tail", response.body().readAllBytes().decodeToString())
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `only allowlisted headers go upstream and no cookie comes back`() {
        pin()
        val (session, url) = open("/echo")
        try {
            val response = get(url, "Range" to "bytes=0-1", "Authorization" to "Bearer secret", "Cookie" to "a=b", "X-Emby-Token" to "tok")
            assertEquals(200, response.statusCode())
            val seen = upstreamHeaders.keys.map { it.lowercase() }
            assertTrue("range" in seen, "ranges are forwarded")
            listOf("authorization", "cookie", "x-emby-token").forEach { assertFalse(it in seen, "$it must not be forwarded") }
            assertTrue(response.headers().firstValue("Set-Cookie").isEmpty, "upstream cookies are not passed to the player")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `a server subtitle maps onto the same session and a foreign url does not`() {
        pin()
        val (session, url) = open("/video.mp4")
        try {
            val mapped = assertNotNull(session.rewrite(source("/subs/1.srt?x=y")))
            assertTrue(mapped.startsWith(url.substringBefore("/video.mp4")) && mapped.endsWith("/subs/1.srt?x=y"))
            assertNull(session.rewrite("https://other.example.com/subs/1.srt"))
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `an external subtitle file of a pinned server is read through the relay`() {
        pin()
        val (session, url) = open("/video.mp4")
        try {
            val playback = PinnedPlayback(url, session)
            val rewritten = playback.rewriteSubtitles(
                listOf(com.nuvio.app.features.streams.StreamSubtitle(url = source("/sub.srt?api_key=k"), language = "eng")),
            ).single()
            assertTrue(rewritten.url.startsWith("http://127.0.0.1:"), "the engine gets the loopback twin")
            val response = get(rewritten.url)
            assertEquals(200, response.statusCode())
            assertContentEquals(subtitle, response.body(), "subtitle text over the pinned TLS")
            assertEquals("https://elsewhere.example/a.srt", playback.rewrite("https://elsewhere.example/a.srt"), "a foreign url is left alone")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `redirects back to the pinned server stay on the relay, public hosts pass through, private ones are refused`() {
        pin()
        val (session, url) = open("/redir-self")
        try {
            val noFollow = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            fun send(path: String) = noFollow.send(
                HttpRequest.newBuilder(URI.create(url.replace("/redir-self", path))).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            val self = send("/redir-self")
            assertEquals(302, self.statusCode())
            val location = self.headers().firstValue("Location").orElse("")
            val resolved = URI.create(url).resolve(location).toString()
            assertTrue(resolved.startsWith(url.substringBefore("/redir-self")) && !resolved.contains(authority), "back on the relay: $location")
            assertEquals(200, get(resolved, "Range" to "bytes=0-9").statusCode().let { if (it == 206) 200 else it }, "following it works")

            val away = send("/redir-other")
            assertEquals(302, away.statusCode())
            assertEquals("https://cdn.example.com/f.mkv?t=1", away.headers().firstValue("Location").orElse(null), "a public host is validated by the player itself")
            assertTrue(away.headers().firstValue("Set-Cookie").isEmpty, "nothing else is passed along")

            assertEquals(502, send("/redir-private").statusCode(), "a redirect into the local network is refused")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `an hls playlist of a pinned server is rewritten so its own segments come back through the relay`() {
        pin()
        val (session, url) = open("/pl.m3u8")
        try {
            val body = get(url).body().decodeToString()
            assertFalse(body.contains(authority), "no segment names the pinned server directly: $body")
            assertTrue(body.contains("http://127.0.0.1:"), "absolute and root-relative segments are on the relay")
            assertTrue(body.contains("seg-relative.ts"), "plain relative references stay relative")
            assertTrue(body.contains("https://cdn.example.com/other.ts"), "other hosts are untouched")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `an unknown token, a foreign host header and an absolute-form target are refused`() {
        pin()
        val (session, url) = open("/video.mp4")
        try {
            val port = URI.create(url).port
            val path = URI.create(url).rawPath
            assertEquals(404, get("http://127.0.0.1:$port/${"0".repeat(32)}/video.mp4").statusCode(), "wrong token")
            assertEquals(403, rawStatus(port, "GET $path HTTP/1.1\r\nHost: evil.example:$port\r\nConnection: close\r\n\r\n"), "rebinding host")
            assertEquals(400, rawStatus(port, "GET http://evil.example/x HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n"), "absolute form")
            assertEquals(200, rawStatus(port, "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n"), "the legitimate request still works")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `disposing the session stops the listener`() {
        pin()
        val (session, url) = open("/video.mp4")
        assertEquals(200, get(url, "Range" to "bytes=0-9").statusCode().let { if (it == 206) 200 else it })
        session.dispose()
        assertTrue(runCatching { get(url) }.isFailure, "nothing listens once the player is gone")
        session.dispose() // idempotent
    }

    @Test
    fun `the listener is not reachable on a LAN address`() {
        pin()
        val (session, url) = open("/video.mp4")
        try {
            val port = URI.create(url).port
            val lan = java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && !it.isLinkLocalAddress }
            if (lan != null) {
                assertTrue(runCatching { Socket().use { it.connect(InetSocketAddress(lan, port), 500) } }.isFailure, "bound to loopback only")
            }
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `a changed certificate or a missing pin is refused, never relayed`() {
        pin()
        val (session, url) = open("/video.mp4")
        try {
            assertTrue(get(url, "Range" to "bytes=0-9").statusCode() < 300)
            val port = tls.port
            tls.stop()
            tls = startServer(port)
            assertNotEquals(trust.pinnedFingerprint(authority), tls.fingerprint, "a different certificate now")
            val after = get(url, "Range" to "bytes=0-9")
            assertTrue(after.statusCode() >= 400, "refused after the certificate changed (was ${after.statusCode()})")
            assertEquals(0, after.body().size, "no media bytes")
        } finally {
            session.dispose()
        }
        // never pinned
        val fresh = ServerTrust(ServerPinStore({ null }, { }, { }))
        val unpinned = runBlocking { PinnedMediaRelay(TrustedServerHttp(fresh)).open(authority) }
        try {
            val r = get(unpinned.rewrite(source("/video.mp4"))!!)
            assertTrue(r.statusCode() >= 400 && r.body().isEmpty(), "a self-signed server nobody pinned is refused (was ${r.statusCode()})")
        } finally {
            unpinned.dispose()
        }
    }

    // ---------------------------------------------------------------- artwork

    @Test
    fun `artwork of a pinned self-signed server loads through the pinned client, other hosts step aside`() = runBlocking<Unit> {
        val factory = PinnedServerImageFetcherFactory(http) { trust.pinnedFingerprint(it) }
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        val options = Options(PlatformContext.INSTANCE)
        val url = source("/img.jpg")
        assertNull(factory.create(url.toUri(), options, loader), "no pin: the normal fetcher keeps this URL")

        pin()
        val fetcher = assertNotNull(factory.create(url.toUri(), options, loader), "pinned: handled by the pinned client")
        val result = withContext(Dispatchers.IO) { fetcher.fetch() } as SourceFetchResult
        assertEquals("image/jpeg", result.mimeType)
        assertContentEquals(jpeg, result.source.source().readByteArray())

        assertNull(factory.create("https://image.tmdb.org/t/p/w500/a.jpg".toUri(), options, loader), "CA-signed hosts keep Coil's default fetcher")
        assertNull(factory.create("http://$authority/img.jpg".toUri(), options, loader), "plain http is not pinned")
        assertNull(factory.create("https://127.0.0.1:${tls.port + 1}/img.jpg".toUri(), options, loader), "another port")
    }

    // ---------------------------------------------------------------- the player's own TLS stack

    @Test
    fun `an ffmpeg reader, libmpv's TLS stack, cannot open the server directly but plays a real mp4 through the relay`() {
        val ffmpeg = which("ffmpeg")
        val ffprobe = which("ffprobe")
        org.junit.Assume.assumeTrue("ffmpeg/ffprobe not installed", ffmpeg != null && ffprobe != null)
        val dir = Files.createTempDirectory("tuvora-mp4").toFile()
        try {
            // moov at the END (no faststart): the reader has to seek with Range requests, like a real player.
            val make = ProcessBuilder(
                ffmpeg, "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=3:size=160x120:rate=10",
                "-pix_fmt", "yuv420p", "-c:v", "libx264", "-y", File(dir, "real.mp4").absolutePath,
            ).redirectErrorStream(true).start()
            org.junit.Assume.assumeTrue("ffmpeg cannot encode a test clip", make.waitFor() == 0)
            realMp4 = File(dir, "real.mp4").readBytes()
            pin()
            val probe = listOf(ffprobe!!, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0")
            val direct = ProcessBuilder(probe + source("/real.mp4")).redirectErrorStream(true).start()
            val directOk = direct.waitFor(30, TimeUnit.SECONDS) && direct.exitValue() == 0
            val (session, url) = open("/real.mp4")
            try {
                val viaRelay = ProcessBuilder(probe + url).redirectErrorStream(true).start()
                assertTrue(viaRelay.waitFor(30, TimeUnit.SECONDS), "ffprobe finished")
                val out = viaRelay.inputStream.readBytes().decodeToString().trim()
                assertEquals(0, viaRelay.exitValue(), "ffprobe through the relay: $out")
                assertTrue(out.toDoubleOrNull()?.let { it in 2.5..3.5 } == true, "the clip's real duration came back: $out")
            } finally {
                session.dispose()
            }
            if (!directOk) println("PROVEN: direct ffprobe refused the self-signed server, relay succeeded")
        } finally {
            realMp4 = null
            dir.deleteRecursively()
        }
    }

    private fun which(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath

    // ---------------------------------------------------------------- fixture

    private fun rawStatus(port: Int, request: String): Int =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(request.encodeToByteArray())
            socket.getOutputStream().flush()
            val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
            line.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }

    private fun startServer(port: Int): Tls {
        val dir = Files.createTempDirectory("tuvora-pinned").toFile()
        val keystore = File(dir, "k.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=other-host.example", "-ext", "san=dns:other-host.example", "-keystore", keystore.absolutePath,
            "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.readBytes().decodeToString() }
        val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
        val cert = ks.getCertificate("s") as X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.httpsConfigurator = HttpsConfigurator(ctx)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        val boundPort = server.address.port
        server.createContext("/video.mp4") { ex -> serveVideo(ex, video) }
        server.createContext("/real.mp4") { ex -> serveVideo(ex, realMp4 ?: video) }
        server.createContext("/img.jpg") { ex ->
            ex.responseHeaders.add("Content-Type", "image/jpeg")
            ex.sendResponseHeaders(200, jpeg.size.toLong())
            ex.responseBody.use { it.write(jpeg) }
        }
        server.createContext("/slow") { ex ->
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.sendResponseHeaders(200, 9)
            ex.responseBody.use { out ->
                out.write("head".encodeToByteArray()); out.flush()
                slowGate.await(10, TimeUnit.SECONDS)
                out.write("-tail".encodeToByteArray())
            }
        }
        server.createContext("/echo") { ex ->
            upstreamHeaders.clear()
            ex.requestHeaders.forEach { (k, v) -> upstreamHeaders[k] = v.toList() }
            ex.responseHeaders.add("Set-Cookie", "session=1")
            ex.sendResponseHeaders(200, 2)
            ex.responseBody.use { it.write("ok".encodeToByteArray()) }
        }
        server.createContext("/redir-self") { ex ->
            ex.responseHeaders.add("Location", "https://127.0.0.1:$boundPort/video.mp4")
            ex.sendResponseHeaders(302, -1)
        }
        server.createContext("/sub.srt") { ex ->
            ex.responseHeaders.add("Content-Type", "application/x-subrip")
            ex.sendResponseHeaders(200, subtitle.size.toLong())
            ex.responseBody.use { it.write(subtitle) }
        }
        server.createContext("/pl.m3u8") { ex ->
            val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nhttps://127.0.0.1:$boundPort/seg-abs.ts\n#EXTINF:4,\n/seg-root.ts\n#EXTINF:4,\nseg-relative.ts\n#EXTINF:4,\nhttps://cdn.example.com/other.ts\n#EXT-X-ENDLIST\n"
            ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            ex.sendResponseHeaders(200, playlist.length.toLong())
            ex.responseBody.use { it.write(playlist.encodeToByteArray()) }
        }
        server.createContext("/redir-private") { ex ->
            ex.responseHeaders.add("Location", "https://192.168.1.10/x")
            ex.sendResponseHeaders(302, -1)
        }
        server.createContext("/redir-other") { ex ->
            ex.responseHeaders.add("Set-Cookie", "s=1")
            ex.responseHeaders.add("Location", "https://cdn.example.com/f.mkv?t=1")
            ex.sendResponseHeaders(302, -1)
        }
        server.start()
        dir.deleteRecursively()
        val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
        return Tls(server, boundPort, fp)
    }

    private fun serveVideo(ex: HttpExchange, video: ByteArray) {
        ex.responseHeaders.add("Content-Type", "video/mp4")
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        val range = ex.requestHeaders.getFirst("Range")
        val head = ex.requestMethod.equals("HEAD", true)
        if (range != null && range.startsWith("bytes=")) {
            val (a, b) = range.removePrefix("bytes=").split('-').let { it[0] to it.getOrElse(1) { "" } }
            val start = a.toInt()
            val end = if (b.isEmpty()) video.size - 1 else b.toInt()
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${video.size}")
            ex.sendResponseHeaders(206, (end - start + 1).toLong())
            if (!head) ex.responseBody.use { it.write(video, start, end - start + 1) } else ex.close()
        } else {
            if (head) ex.responseHeaders.add("Content-Length", video.size.toString())
            ex.sendResponseHeaders(200, if (head) -1 else video.size.toLong())
            if (!head) ex.responseBody.use { it.write(video) } else ex.close()
        }
    }
}
