package com.nuvio.app.features.addons

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.network.DesktopIPv4FirstDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.network_request_failed_http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.GzipSource
import okio.buffer
import org.jetbrains.compose.resources.getString
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal actual object AddonStorage {
    private val store = DesktopStorage.store("nuvio_addons")
    private val json = Json { ignoreUnknownKeys = true }

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        store.getString("installed_addon_urls_$profileId")
            ?.let { payload -> runCatching { json.decodeFromString<List<String>>(payload) }.getOrNull() }
            ?: emptyList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        store.putString("installed_addon_urls_$profileId", json.encodeToString(urls))
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        store.getString("addon_enabled_states_$profileId")
            ?.let { payload -> runCatching { json.decodeFromString<Map<String, Boolean>>(payload) }.getOrNull() }
            ?: emptyMap()

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        store.putString("addon_enabled_states_$profileId", json.encodeToString(states))
    }
}

private val desktopHttpClient = OkHttpClient.Builder()
    .dns(DesktopIPv4FirstDns())
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

private const val truncationSuffix = "\n...[truncated]"

// dnsProvider (DoH for ISP-blocked IPTV playlists) is not wired on desktop yet — the
// system resolver is used regardless; port PlaylistDns + okhttp-dnsoverhttps to enable.
actual suspend fun httpGetText(url: String, dnsProvider: String?): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json"),
    )

actual suspend fun httpPostJson(url: String, body: String): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ),
        body = body,
    )

// [dnsProvider] is Android-only (no per-app DNS hook on the desktop JVM client) — ignored here.
actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
    dnsProvider: String?,
): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json") + headers,
    )

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ) + headers,
        body = body,
    )

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
    maxResponseBodyBytes: Int,
): RawHttpResponse = withContext(Dispatchers.IO) {
    val client = if (followRedirects) {
        desktopHttpClient
    } else {
        desktopHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
    val request = buildDesktopRequest(method, url, headers, body)

    client.newCall(request).execute().use { response ->
        RawHttpResponse(
            status = response.code,
            statusText = response.message,
            url = response.request.url.toString(),
            body = readResponseBodyLimited(response.body, maxResponseBodyBytes),
            headers = response.headers.toMultimap().mapValues { (_, values) ->
                values.joinToString(",")
            }.mapKeys { (name, _) ->
                name.lowercase()
            },
        )
    }
}

private suspend fun executeTextRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
): String = withContext(Dispatchers.IO) {
    val request = buildDesktopRequest(method, url, headers, body)
    desktopHttpClient.newCall(request).execute().use { response ->
        val payload = readResponseBody(response.body)
        if (!response.isSuccessful) {
            error(runBlocking { getString(Res.string.network_request_failed_http, response.code) })
        }
        if (payload.isBlank()) {
            throw EmptyResponseBodyException(runBlocking { getString(Res.string.network_empty_response_body) })
        }
        payload
    }
}

private fun buildDesktopRequest(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
): Request {
    val normalizedMethod = method.trim().uppercase().ifBlank { "GET" }
    val sanitizedHeaders = headers.withoutAcceptEncoding()
    val builder = Request.Builder().url(url.encodeUnsafeHttpUrlCharacters())
    sanitizedHeaders.forEach { (key, value) ->
        if (key.isNotBlank() && value.isNotBlank()) {
            builder.header(key, value)
        }
    }

    return if (requestAllowsBody(normalizedMethod)) {
        val contentType = sanitizedHeaders.getHeaderIgnoreCase("Content-Type")
            ?: if (normalizedMethod == "POST") "application/x-www-form-urlencoded" else "application/json"
        builder.method(
            normalizedMethod,
            body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType()),
        )
    } else {
        builder.method(normalizedMethod, null)
    }.build()
}

private fun requestAllowsBody(method: String): Boolean =
    when (method.uppercase()) {
        "POST", "PUT", "PATCH", "DELETE" -> true
        else -> false
    }

private fun Map<String, String>.withoutAcceptEncoding(): Map<String, String> =
    entries
        .filterNot { (key, _) -> key.equals("Accept-Encoding", ignoreCase = true) }
        .associate { (key, value) -> key to value }

private fun Map<String, String>.getHeaderIgnoreCase(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

private data class LimitedReadResult(
    val bytes: ByteArray,
    val truncated: Boolean,
)

private fun readAtMostBytes(stream: InputStream, maxBytes: Int): LimitedReadResult {
    val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    var truncated = false

    while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }

    if (remaining == 0) {
        truncated = stream.read() != -1
    }

    return LimitedReadResult(out.toByteArray(), truncated)
}

private fun readResponseBodyLimited(body: ResponseBody?, maxBytes: Int): String {
    if (body == null) return ""
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    val readResult = body.byteStream().use { stream ->
        readAtMostBytes(stream, maxBytes.coerceAtLeast(0))
    }
    val decoded = runCatching {
        String(readResult.bytes, charset)
    }.getOrElse {
        String(readResult.bytes, Charsets.UTF_8)
    }
    return if (readResult.truncated) decoded + truncationSuffix else decoded
}

/**
 * Whole-body read, bounded by [MaxTextResponseBytes].
 *
 * `body.bytes()` asks for the entire response as ONE array, which is how a large Xtream catalog
 * produced `Failed to allocate a 26891064 byte allocation` on the Android twin. Bulk lists stream
 * now, so this cap is a backstop against anything else on this path growing without warning.
 *
 * A declared Content-Length over the cap is refused before a single byte is read — the cheapest
 * possible outcome. Within the cap it still goes through `bytes()`, whose one exact-sized
 * allocation beats a growing buffer that doubles and copies. Only a length-less (chunked)
 * response needs the incremental read, and those are small in practice.
 */
private fun readResponseBody(body: ResponseBody?): String {
    if (body == null) return ""
    val declaredLength = body.contentLength()
    if (declaredLength > MaxTextResponseBytes) throw responseTooLarge(declaredLength)

    val bytes = if (declaredLength >= 0) {
        body.bytes()
    } else {
        val readResult = body.byteStream().use { stream ->
            readAtMostBytes(stream, MaxTextResponseBytes)
        }
        // readAtMostBytes peeks one byte past the cap, so an overrun is caught without
        // buffering the excess.
        if (readResult.truncated) throw responseTooLarge(-1)
        readResult.bytes
    }

    return runCatching {
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        String(bytes, charset)
    }.getOrElse {
        String(bytes, Charsets.UTF_8)
    }
}

private fun responseTooLarge(declaredLength: Long): ResponseTooLargeException {
    val limitMb = MaxTextResponseBytes / (1024 * 1024)
    val size = if (declaredLength >= 0) "$declaredLength bytes" else "response"
    return ResponseTooLargeException("Body too large to read as text ($size, limit ${limitMb}MB)")
}

actual suspend fun httpStreamLines(
    url: String,
    userAgent: String?,
    dnsProvider: String?,
    headers: Map<String, String>,
    onLine: (String) -> Unit,
): Unit = withContext(Dispatchers.IO) {
    val builder = Request.Builder().url(url).get()
    if (!userAgent.isNullOrBlank()) builder.header("User-Agent", userAgent)
    for ((k, v) in headers) builder.header(k, v)
    // OkHttp transparently gunzips a `Content-Encoding: gzip` response when we don't set
    // Accept-Encoding ourselves — so leave it unset. For a URL that returns a raw .gz body
    // (no Content-Encoding header), we sniff the gzip magic bytes and wrap manually.
    // dnsProvider (per-playlist DoH) is not wired on desktop — system resolver only.
    val request = builder.build()
    desktopHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            error("Request failed with HTTP ${response.code}")
        }
        val body = response.body ?: return@use
        val rawSource = body.source()
        val encoding = response.header("Content-Encoding")?.lowercase()
        val looksGzipped = encoding == null && runCatching {
            rawSource.request(2)
            rawSource.buffer.size >= 2 &&
                rawSource.buffer[0] == 0x1f.toByte() && rawSource.buffer[1] == 0x8b.toByte()
        }.getOrDefault(false)
        val source: okio.BufferedSource = if (looksGzipped) {
            GzipSource(rawSource).buffer()
        } else {
            rawSource
        }
        streamBoundedLines(source, onLine)
    }
}

/**
 * Largest slice handed to [onLine] when the document has no newline in reach. Big enough that a
 * normal line-delimited playlist/guide is unaffected, small enough that a newline-free document
 * can't blow the heap.
 */
private const val MAX_LINE_BYTES = 1L * 1024 * 1024

/**
 * Line reader that cannot OOM on a newline-free document.
 *
 * `readUtf8Line()` buffers until it finds a '\n' — on a MINIFIED XMLTV guide (the whole document on
 * one line, which real providers do serve) that means buffering the entire feed: a 108MB allocation
 * against a ~192MB heap, which killed the app on launch. Here the search for the newline is capped,
 * and when none is found within the cap the buffered slice is emitted as its own chunk.
 *
 * Safe for both consumers: M3U lines are far below the cap so they still arrive whole, and the
 * XMLTV tokenizer explicitly accepts chunk boundaries falling anywhere, even mid-tag.
 */
internal fun streamBoundedLines(source: okio.BufferedSource, onLine: (String) -> Unit) {
    while (true) {
        val newline = source.indexOf('\n'.code.toByte(), 0L, MAX_LINE_BYTES)
        if (newline != -1L) {
            val line = source.readUtf8(newline)
            source.skip(1)                      // drop the '\n'
            onLine(line.removeSuffix("\r"))
            continue
        }
        if (!source.request(1)) return          // EOF
        // No newline within the cap: emit what we have, cut on a character boundary so a
        // multi-byte glyph is never split across two chunks.
        val cut = utf8SafeCut(source.buffer, MAX_LINE_BYTES)
        if (cut <= 0L) return
        onLine(source.readUtf8(cut))
    }
}

/** Largest byte count <= [max] that doesn't land inside a multi-byte UTF-8 sequence. */
private fun utf8SafeCut(buffer: okio.Buffer, max: Long): Long {
    var n = minOf(max, buffer.size)
    if (n >= buffer.size) return buffer.size    // whole buffer: nothing follows to split
    var walked = 0
    while (n > 0 && walked < 4 && (buffer[n].toInt() and 0xC0) == 0x80) {
        n--
        walked++
    }
    return if (n > 0) n else minOf(max, buffer.size)
}
