package com.nuvio.app.features.servers

import com.nuvio.app.features.common.lifecycle.Disposable
import io.ktor.client.request.prepareRequest
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray

/**
 * A loopback relay that lets a player which cannot take a certificate pin (libmpv/ffmpeg on Android and iOS) read from a
 * pinned self-signed media server. The relay speaks plain http on 127.0.0.1 and fetches the real bytes over the pinned
 * client, so the certificate IS verified (against this device's pin) - verification is never turned off.
 *
 * Hardening, all enforced by [RelayRequestPolicy] and covered by tests:
 *  - binds 127.0.0.1 only, on an ephemeral port, one listener per session;
 *  - every URL carries a per-session 128-bit random token as its first path segment; anything else is 404;
 *  - forwards ONLY to the one `host:port` the session was opened for (the target host is never taken from the request),
 *    GET and HEAD only, no request bodies, a fixed header allow-list both ways;
 *  - Range and streaming: the body is piped in chunks, never buffered whole (4K files are tens of GB);
 *  - bounded: header size/lines, concurrent connections, idle read of the request;
 *  - closes with its owner ([Disposable], registered in an EffectScope by the caller); never logs URLs or tokens.
 */
internal class PinnedMediaRelay(
    private val http: ServerHttp = TrustedServerHttp.shared,
    private val maxConcurrentConnections: Int = 16,
) {
    /** Opens a session for [authority] (`host:port`, https). */
    suspend fun open(authority: String): RelaySession {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val selector = SelectorManager(Dispatchers.Default)
        val server = try {
            aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
        } catch (error: Throwable) {
            selector.close()
            job.cancel()
            throw error
        }
        val port = (server.localAddress as InetSocketAddress).port
        val session = RelaySession(authority.lowercase(), port, secureRandomHex(16), scope, selector, server)
        val limit = Semaphore(maxConcurrentConnections)
        scope.launch {
            try {
                while (isActive) {
                    val socket = server.accept()
                    scope.launch {
                        if (!limit.tryAcquire()) {
                            socket.closeQuietly()
                            return@launch
                        }
                        try {
                            handle(session, socket)
                        } finally {
                            limit.release()
                            socket.closeQuietly()
                        }
                    }
                }
            } catch (_: CancellationException) {
            } catch (_: Throwable) {
                // listener closed: the session is over
            }
        }
        return session
    }

    private suspend fun handle(session: RelaySession, socket: Socket) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = false)
        try {
            val head = withTimeoutOrNull(REQUEST_HEAD_TIMEOUT_MS) { readHead(input) }
            if (head == null) {
                respond(output, 408)
                return
            }
            if (head.oversize) {
                respond(output, 431)
                return
            }
            when (val parsed = RelayRequestPolicy.parse(head.requestLine, head.headers, session.token, session.port)) {
                is RelayRequestPolicy.Result.Reject -> respond(output, parsed.status)
                is RelayRequestPolicy.Result.Ok -> forward(session, parsed, output)
            }
        } catch (_: CancellationException) {
        } catch (_: Throwable) {
            runCatching { respond(output, 502) }
        } finally {
            runCatching { output.flushAndClose() }
        }
    }

    private class Head(val requestLine: String, val headers: List<String>, val oversize: Boolean)

    private suspend fun readHead(input: ByteReadChannel): Head? {
        val requestLine = input.readUTF8Line(RelayRequestPolicy.MAX_HEADER_BYTES) ?: return null
        val headers = mutableListOf<String>()
        var bytes = requestLine.length
        while (true) {
            val line = input.readUTF8Line(RelayRequestPolicy.MAX_HEADER_BYTES) ?: return null
            if (line.isEmpty()) break
            bytes += line.length
            if (headers.size >= RelayRequestPolicy.MAX_HEADER_LINES || bytes > RelayRequestPolicy.MAX_HEADER_BYTES) {
                return Head(requestLine, headers, oversize = true)
            }
            headers += line
        }
        return Head(requestLine, headers, oversize = false)
    }

    private suspend fun forward(session: RelaySession, request: RelayRequestPolicy.Result.Ok, output: ByteWriteChannel) {
        val url = "https://${session.authority}${request.path}"
        val client = http.clientFor(url)
        try {
            client.prepareRequest(url) {
                method = HttpMethod.parse(request.method)
                request.headers.forEach { (name, value) -> headers.append(name, value) }
                headers.append("Accept-Encoding", "identity")
                // The client has a whole-request timeout for API calls; a media stream legitimately runs for hours.
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = STREAM_IDLE_TIMEOUT_MS
                }
            }.execute { response ->
                var status = response.status.value
                var location: String? = null
                if (RelayRedirectPolicy.isRedirect(status)) {
                    // Back to the pinned server -> through this relay; to a public https host -> the player follows it itself,
                    // validating that host normally; anything else -> refused.
                    when (val decision = RelayRedirectPolicy.decide(status, response.headers["Location"], session.authority)) {
                        is RelayRedirectPolicy.Decision.ViaRelay -> location = "/" + session.token + decision.path
                        is RelayRedirectPolicy.Decision.PassThrough -> location = decision.location
                        RelayRedirectPolicy.Decision.Refuse -> status = 502
                    }
                }
                val isRedirectAnswer = location != null || status == 502
                val upstream = response.headers.entries().associate { (name, values) -> name to values }
                val head = StringBuilder("HTTP/1.1 $status ${HttpStatusCode.fromValue(status).description}\r\n")
                if (isRedirectAnswer) {
                    // A bare redirect: Location and nothing else of ours or the server's.
                    location?.let { head.append("Location: ").append(it).append("\r\n") }
                    head.append("Content-Length: 0\r\nConnection: close\r\n\r\n")
                    output.writeStringUtf8(head.toString())
                    output.flush()
                    return@execute
                }
                val contentType = response.headers["Content-Type"]
                if (request.method == "GET" && status == 200 && HlsPlaylistRewriter.isPlaylist(contentType, request.path)) {
                    // Playlists only, size-capped: absolute references to the pinned server go back through the relay.
                    val bytes = response.bodyAsChannel().readRemaining(HlsPlaylistRewriter.MAX_PLAYLIST_BYTES + 1L).readByteArray()
                    if (bytes.size > HlsPlaylistRewriter.MAX_PLAYLIST_BYTES) {
                        respond(output, 502)
                        return@execute
                    }
                    val rewritten = HlsPlaylistRewriter
                        .rewrite(bytes.decodeToString(), session.authority, "http://127.0.0.1:${session.port}/${session.token}")
                        .encodeToByteArray()
                    RelayRequestPolicy.responseHeaders(upstream)
                        .filterNot { it.first == "Content-Length" || it.first == "Content-Range" }
                        .forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
                    head.append("Content-Length: ").append(rewritten.size).append("\r\nConnection: close\r\n\r\n")
                    output.writeStringUtf8(head.toString())
                    output.writeFully(rewritten, 0, rewritten.size)
                    output.flush()
                    return@execute
                }
                RelayRequestPolicy.responseHeaders(upstream).forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
                head.append("Connection: close\r\n\r\n")
                output.writeStringUtf8(head.toString())
                if (request.method == "GET") {
                    val body = response.bodyAsChannel()
                    val buffer = ByteArray(COPY_CHUNK_BYTES)
                    while (true) {
                        val read = body.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read == 0) continue
                        output.writeFully(buffer, 0, read)
                        output.flush()
                    }
                }
                output.flush()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Upstream failure before any byte was written: say so; never include the URL or the cause.
            runCatching { respond(output, if (http.certificateFailure(url) != null) 495 else 502) }
        }
    }

    private suspend fun respond(output: ByteWriteChannel, status: Int) {
        output.writeStringUtf8("HTTP/1.1 $status ${HttpStatusCode.fromValue(status).description}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        output.flush()
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        const val REQUEST_HEAD_TIMEOUT_MS = 10_000L
        const val STREAM_IDLE_TIMEOUT_MS = 60_000L
        const val COPY_CHUNK_BYTES = 64 * 1024

        val shared: PinnedMediaRelay by lazy { PinnedMediaRelay() }
    }
}

/** One open listener for one pinned server authority. [dispose] closes it and every connection it is serving. */
internal class RelaySession internal constructor(
    val authority: String,
    val port: Int,
    internal val token: String,
    private val scope: CoroutineScope,
    private val selector: SelectorManager,
    private val server: ServerSocket,
) : Disposable {
    /** Maps a URL of this session's authority to its loopback twin, or null for a URL of any other host. */
    fun rewrite(url: String): String? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        if (serverAuthority(url) != authority) return null
        val rest = url.substringAfter("://").substringAfter('/', "")
        return "http://127.0.0.1:$port/$token/$rest"
    }

    override fun dispose() {
        runCatching { server.close() }
        runCatching { selector.close() }
        scope.cancel()
    }

    override fun toString(): String = "RelaySession(port=$port)"
}
