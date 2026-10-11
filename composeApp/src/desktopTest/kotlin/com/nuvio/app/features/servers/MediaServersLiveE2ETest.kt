package com.nuvio.app.features.servers

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The upstream servers stack (Jellyfin + Emby providers, TOFU pin, Quick Connect, the loopback relay, artwork) against the REAL
 * Jellyfin 12.2 / Emby 4.10 docker rig (scratchpad e2e-media). Skipped unless the rig's env is present:
 *   source e2e-media/env.sh; export TUVORA_MS_E2E_HARNESS=<e2e-media dir>
 * Runs with the in-memory server storage of the test task (no Keychain, no real app data). The rig's cert A is rotated and
 * restored by the rotation test through the rig's own scripts.
 */
class MediaServersLiveE2ETest {
    private val harness: File? = System.getenv("TUVORA_MS_E2E_HARNESS")?.let(::File)?.takeIf { it.isDirectory }
    private val jfSpec = System.getenv("TUVORA_MS_E2E_JELLYFIN_TLS")
    private val embySpec = System.getenv("TUVORA_MS_E2E_EMBY")
    private val http: HttpClient = HttpClient.newHttpClient()

    private data class Spec(val url: String, val user: String, val pass: String)

    private fun parse(spec: String): Spec = spec.split('|').let { Spec(it[0], it[1], it[2]) }

    private fun fingerprint(front: String): String {
        val lines = File(harness!!, "fingerprints.txt").readLines()
        val start = lines.indexOfFirst { it.startsWith("front $front") }
        return lines.drop(start + 1).first { it.trim().startsWith("cert sha256:") }.substringAfter(':').trim().replace(":", "")
    }

    private fun script(name: String, vararg args: String): String {
        val p = ProcessBuilder(listOf(File(harness!!, name).absolutePath) + args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "$name finished")
        assertEquals(0, p.exitValue(), "$name: $out")
        return out
    }

    @BeforeTest
    fun setUp() {
        org.junit.Assume.assumeTrue("media-server rig env missing", harness != null && jfSpec != null && embySpec != null)
        ServerRepository.clearLocalState()
    }

    @AfterTest
    fun tearDown() {
        if (harness != null) runCatching { script("rotate-cert.sh", "--restore") }
        runCatching { ServerRepository.clearLocalState() }
    }

    private fun relayFetch(relay: RelaySession, url: String, vararg headers: Pair<String, String>): HttpResponse<ByteArray> {
        val rewritten = assertNotNull(relay.rewrite(url), "relay maps $url")
        val b = HttpRequest.newBuilder(URI.create(rewritten)).GET()
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    // ------------------------------------------------------------------ Jellyfin over a self-signed front

    @Test
    fun `jellyfin behind a self-signed front - pin, sign in, browse, artwork, ranged video and redirects through the relay`() = runBlocking<Unit> {
        val spec = parse(jfSpec!!)
        val provider = assertNotNull(ServerProviders.forId("jellyfin"))
        val authority = serverAuthority(spec.url)!!

        // 1. nothing pinned: refused, and the user is told WHICH certificate (must equal the rig's front A)
        val first = assertFailsWith<ServerCertificateException> { provider.signIn(spec.url, spec.user, spec.pass) }
        assertEquals(fingerprint("A ORIGINAL"), first.fingerprint, "the prompt shows the real certificate fingerprint")
        assertEquals(ServerFailure.CERTIFICATE_UNTRUSTED, first.failure)

        // 2. front B (another key) is not covered by A's pin
        ServerTrust.shared.pin(first.authority, first.fingerprint)
        val otherUrl = spec.url.replace(":18443", ":18444")
        val b = assertFailsWith<ServerCertificateException> { provider.signIn(otherUrl, spec.user, spec.pass) }
        assertEquals(fingerprint("B (port 18444, fixed)"), b.fingerprint)

        // 3. password sign-in + browse
        val connection = ServerRepository.connect(provider, spec.url, spec.user, spec.pass)
        assertEquals(spec.user, connection.userName)
        assertTrue(connection.libraries.any { it.kind == ServerMediaKind.MOVIE }, "movie library: ${connection.libraries}")
        assertTrue(connection.libraries.any { it.kind == ServerMediaKind.SERIES }, "series library")
        val session = assertNotNull(ServerRepository.session(connection.id))
        val movies = connection.libraries.first { it.kind == ServerMediaKind.MOVIE }
        val page = provider.libraryPage(session, movies, 0, 20)
        val title = assertNotNull(page.items.firstOrNull { it.preview.name.contains("Test Movie") }, "Test Movie listed: ${page.items.map { it.preview.name }}")
        val itemId = ServerItemRef.parse(title.preview.id)!!.itemId

        // 4. artwork over the pinned client (Coil fetcher)
        val poster = assertNotNull(title.preview.poster, "poster url")
        val fetcher = PinnedServerImageFetcherFactory().create(poster.toUri(), Options(PlatformContext.INSTANCE), ImageLoader.Builder(PlatformContext.INSTANCE).build())
        val art = (assertNotNull(fetcher, "pinned host is handled by the pinned fetcher").fetch() as SourceFetchResult)
        val artBytes = art.source.source().readByteArray()
        assertTrue(artBytes.size > 100, "artwork bytes: ${artBytes.size}")
        assertTrue(art.mimeType?.startsWith("image/") == true, "image mime: ${art.mimeType}")

        // 5. direct-play video through the relay: Range, seek near the end, byte-exact
        val candidate = provider.candidates(session, itemId).first()
        val playback = provider.preparePlayback(session, ServerPlaybackRequest(candidate.target, ServerPlayerCapabilities(directPlayAll = true)))
        assertTrue(playback.url.startsWith("https://$authority") || playback.url.startsWith("https://localhost:18443"), "direct url on the pinned host")
        val relay = PinnedMediaRelay().open(authority)
        try {
            val head = relayFetch(relay, playback.url, "Range" to "bytes=0-4095")
            assertEquals(206, head.statusCode())
            assertEquals("ftyp", head.body().copyOfRange(4, 8).decodeToString(), "an mp4 container arrives intact")
            val total = head.headers().firstValue("Content-Range").get().substringAfter('/').toLong()
            val seek = relayFetch(relay, playback.url, "Range" to "bytes=${total - 1024}-${total - 1}")
            assertEquals(206, seek.statusCode(), "seek to the end")
            assertEquals(1024, seek.body().size)
            val direct = relayFetch(relay, playback.url, "Range" to "bytes=${total - 1024}-${total - 1}")
            assertTrue(seek.body().contentEquals(direct.body()), "stable bytes")

            // 6. the rig's relative same-server redirect stays on the relay and still ranges
            val redirected = "https://$authority/e2e/redir/Videos/$itemId/stream?static=true&MediaSourceId=$itemId"
            val viaRedirect = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(
                HttpRequest.newBuilder(URI.create(relay.rewrite(redirected)!!)).header("Range", "bytes=0-99").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(206, viaRedirect.statusCode(), "the java client follows the relative Location back through the relay")
            assertEquals(100, viaRedirect.body().size)

            // 7. a redirect to another (private) authority is refused, not followed
            val away = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
                HttpRequest.newBuilder(URI.create(relay.rewrite("https://$authority/e2e/redir-other/Videos/$itemId/stream?static=true&MediaSourceId=$itemId")!!)).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(502, away.statusCode(), "private cross-authority redirect refused")
            assertTrue(away.headers().firstValue("Location").isEmpty)
        } finally {
            relay.dispose()
        }
    }

    @Test
    fun `a rotated certificate is refused as changed by the API client and the relay, and trusting it again works`() = runBlocking<Unit> {
        val spec = parse(jfSpec!!)
        val provider = assertNotNull(ServerProviders.forId("jellyfin"))
        val authority = serverAuthority(spec.url)!!
        val original = assertFailsWith<ServerCertificateException> { provider.signIn(spec.url, spec.user, spec.pass) }
        ServerTrust.shared.pin(authority, original.fingerprint)
        val connection = ServerRepository.connect(provider, spec.url, spec.user, spec.pass)
        val session = assertNotNull(ServerRepository.session(connection.id))
        val relay = PinnedMediaRelay().open(authority)
        try {
            assertEquals(200, relayFetch(relay, "https://$authority/e2e/health").statusCode())
            script("rotate-cert.sh")
            val changed = assertFailsWith<ServerCertificateException>("API client refuses the rotated certificate") { provider.libraries(session) }
            assertEquals(ServerFailure.CERTIFICATE_CHANGED, changed.failure)
            assertNotEquals(original.fingerprint, changed.fingerprint)
            assertTrue(relayFetch(relay, "https://$authority/e2e/health").statusCode() >= 400, "the relay refuses it too")
            // the user re-trusts the new certificate
            ServerTrust.shared.pin(authority, changed.fingerprint)
            assertEquals(200, relayFetch(relay, "https://$authority/e2e/health").statusCode())
            assertTrue(provider.libraries(session).isNotEmpty())
        } finally {
            relay.dispose()
            script("rotate-cert.sh", "--restore")
        }
    }

    @Test
    fun `quick connect - code, approval from the server side, this device's own token`() = runBlocking<Unit> {
        val spec = parse(jfSpec!!)
        val provider = assertNotNull(ServerProviders.forId("jellyfin"))
        val authority = serverAuthority(spec.url)!!
        val first = first(spec.url, provider)
        ServerTrust.shared.pin(authority, first.fingerprint)
        assertTrue(provider.quickConnectAvailable(spec.url), "Quick Connect is on")
        val challenge = provider.quickConnectStart(spec.url)
        assertTrue(challenge.code.isNotBlank())
        script("approve-quickconnect.sh", challenge.code)
        var state = QuickConnectPoll.PENDING
        repeat(10) { if (state == QuickConnectPoll.PENDING) { state = provider.quickConnectPoll(challenge); if (state == QuickConnectPoll.PENDING) delay(1000) } }
        assertEquals(QuickConnectPoll.AUTHORISED, state)
        val connection = ServerRepository.connectWithQuickConnect(provider, challenge)
        assertEquals("tuvora_admin", connection.userName, "approved without a user: signed in as the admin")
        val token = assertNotNull(ServerRepository.session(connection.id)).token
        assertTrue(token.isNotBlank() && token != spec.pass)
    }

    private suspend fun first(url: String, provider: ServerProvider): ServerCertificateException =
        runCatching { provider.signIn(url, "x", "y") }.exceptionOrNull() as ServerCertificateException

    // ------------------------------------------------------------------ Emby

    @Test
    fun `emby over plain http and over the self-signed front - sign in, browse, direct play range, no quick connect`() = runBlocking<Unit> {
        val emby = parse(embySpec!!)
        val provider = assertNotNull(ServerProviders.forId("emby"))
        assertFalse(provider.supports(ServerCapability.QUICK_CONNECT), "Emby has no Quick Connect")
        assertFalse(provider.quickConnectAvailable(emby.url))

        val plain = ServerRepository.connect(provider, emby.url, emby.user, emby.pass)
        assertTrue(plain.libraries.isNotEmpty(), "libraries: ${plain.libraries}")
        val session = assertNotNull(ServerRepository.session(plain.id))
        val movies = plain.libraries.first { it.kind == ServerMediaKind.MOVIE }
        val titles = provider.libraryPage(session, movies, 0, 20).items
        assertTrue(titles.isNotEmpty(), "movies listed")
        val itemId = ServerItemRef.parse(titles.first().preview.id)!!.itemId
        val candidate = provider.candidates(session, itemId).first()
        val playback = provider.preparePlayback(session, ServerPlaybackRequest(candidate.target, ServerPlayerCapabilities(directPlayAll = true)))
        val ranged = http.send(
            HttpRequest.newBuilder(URI.create(playback.url)).header("Range", "bytes=0-1023").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(206, ranged.statusCode(), "plain http direct play with a Range")
        assertEquals("ftyp", ranged.body().copyOfRange(4, 8).decodeToString())

        // the same Emby behind front A's :18445 listener: self-signed, pinned, played through the relay
        val tlsUrl = "https://localhost:18445"
        val tlsAuthority = serverAuthority(tlsUrl)!!
        val refused = assertFailsWith<ServerCertificateException> { provider.signIn(tlsUrl, emby.user, emby.pass) }
        assertEquals(fingerprint("A ORIGINAL"), refused.fingerprint, "same cert A on the Emby listener")
        ServerTrust.shared.pin(tlsAuthority, refused.fingerprint)
        val viaTls = ServerRepository.connect(provider, tlsUrl, emby.user, emby.pass)
        val tlsSession = assertNotNull(ServerRepository.session(viaTls.id))
        val tlsItem = ServerItemRef.parse(provider.libraryPage(tlsSession, viaTls.libraries.first { it.kind == ServerMediaKind.MOVIE }, 0, 5).items.first().preview.id)!!.itemId
        val tlsPlay = provider.preparePlayback(tlsSession, ServerPlaybackRequest(provider.candidates(tlsSession, tlsItem).first().target, ServerPlayerCapabilities(directPlayAll = true)))
        val relay = PinnedMediaRelay().open(tlsAuthority)
        try {
            val r = relayFetch(relay, tlsPlay.url, "Range" to "bytes=0-4095")
            assertEquals(206, r.statusCode())
            assertEquals("ftyp", r.body().copyOfRange(4, 8).decodeToString())
        } finally {
            relay.dispose()
        }
    }
}
