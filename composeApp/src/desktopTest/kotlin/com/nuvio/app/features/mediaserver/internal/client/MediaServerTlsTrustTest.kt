package com.nuvio.app.features.mediaserver.internal.client

import com.nuvio.app.features.mediaserver.internal.store.MediaServerTrustStore
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The REAL TLS enforcement (JVM): a local HTTPS server with a self-signed certificate, reached through the
 * platform client factory ([createMediaServerHttpClient]) exactly as the app does. Pins the trust-on-first-use
 * contract end to end: refused with the certificate's fingerprint until the user pins it; accepted once pinned (even
 * though the certificate is for another host name - a home server reached by IP); refused again - as "certificate
 * changed" - when the certificate is replaced.
 */
class MediaServerTlsTrustTest {
    private class Tls(val server: HttpsServer, val fingerprint: String, val port: Int) {
        fun stop() = server.stop(0)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun start(port: Int, cn: String): Tls {
        val dir = Files.createTempDirectory("tuvora-tls").toFile()
        val keystore = File(dir, "k.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=$cn", "-ext", "san=dns:$cn", "-keystore", keystore.absolutePath, "-storetype", "PKCS12",
            "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.readBytes().decodeToString() }
        val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
        val cert = ks.getCertificate("s") as X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.httpsConfigurator = HttpsConfigurator(ctx)
        server.createContext("/") { ex ->
            val body = """{"Id":"abc123","ServerName":"TLS test","Version":"12.2.0","ProductName":"Jellyfin Server"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        dir.deleteRecursively()
        val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
        return Tls(server, fp, port)
    }

    private class Disk { var json: String? = null }

    @Test
    fun selfSignedIsRefusedUntilPinnedThenAcceptedAndARotatedCertificateIsRefusedAgain() = runBlocking<Unit> {
        val port = freePort()
        val disk = Disk()
        val trust = MediaServerTrust(MediaServerTrustStore({ disk.json }, { disk.json = it }, { disk.json = null }))
        val http = AuthorityRoutingHttp(trust)
        val authority = "127.0.0.1:$port"
        val url = "https://$authority/System/Info/Public"
        var tls = start(port, "other-host.example")
        try {
            // 1. system validation fails: refused, and the user is told WHICH certificate to trust
            val first = assertFailsWith<MediaServerException.CertificateUntrusted> { http.execute(MediaServerRequest("GET", url, timeoutMs = 20_000)) }
            assertEquals(tls.fingerprint, first.fingerprint, "the exact leaf the server presented")
            assertEquals(authority, first.authority)

            // 2. the user trusts it: the same request now works (hostname verification waived for THIS pinned certificate only)
            trust.pin(authority, tls.fingerprint)
            val ok = http.execute(MediaServerRequest("GET", url, timeoutMs = 20_000))
            assertEquals(200, ok.status)
            assertTrue(ok.body.contains("abc123"))

            // 3. the server presents a different certificate: refused again, as a CHANGED certificate (never silently accepted)
            tls.stop()
            tls = start(port, "other-host.example")
            val changed = assertFailsWith<MediaServerException.CertificateUntrusted> { http.execute(MediaServerRequest("GET", url, timeoutMs = 20_000)) }
            assertEquals(tls.fingerprint, changed.fingerprint)
            assertTrue(changed.fingerprint != trust.pinnedFingerprint(authority), "it is not the pinned one")

            // 4. unpinned servers stay refused (a pin is per host:port)
            val other = start(freePort(), "another.example")
            try {
                assertFailsWith<MediaServerException.CertificateUntrusted> { http.execute(MediaServerRequest("GET", "https://127.0.0.1:${other.port}/x", timeoutMs = 20_000)) }
            } finally {
                other.stop()
            }
        } finally {
            tls.stop()
        }
    }
}
