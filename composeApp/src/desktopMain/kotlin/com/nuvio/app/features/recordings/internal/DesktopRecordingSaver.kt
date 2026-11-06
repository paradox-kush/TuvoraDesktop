package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.recordings.api.KnownSizeDecision
import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.api.RecordingSaver
import com.nuvio.app.features.recordings.api.SaveOutcome
import com.nuvio.app.features.recordings.api.SaveRequest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Saves a catch-up programme with the JDK HttpClient, the way the desktop downloader does (same
 * conventions: redirects followed, 60 s connect). Candidates are tried in order; the request
 * headers are the channel's own (they already carry the provider user-agent), nothing is invented.
 * Writes `target.part`, resumes it with a Range request when asked (restarting if the server
 * answers 200), and moves it onto the target when whole.
 *
 * Stalls: a watchdog reads [RecordingStallPolicy] on the monotonic clock and closes the body when
 * no byte arrived for [stallMs]; the response-header wait is bounded by the same limit.
 */
internal class DesktopRecordingSaver(
    private val isOnline: () -> Boolean = ::hasUsableNetwork,
    private val client: HttpClient = defaultClient,
    private val stallMs: Long = RecordingStallPolicy.STALL_MS,
    private val watchdogTickMs: Long = WATCHDOG_TICK_MS,
) : RecordingSaver {

    override suspend fun save(req: SaveRequest, progress: (Long) -> Unit): SaveOutcome = withContext(Dispatchers.IO) {
        val target = File(req.targetPath)
        val part = File(req.targetPath + ".part")
        part.parentFile?.mkdirs()
        var best: SaveOutcome.Failed? = null
        for (url in req.candidates) {
            ensureActive()
            when (val r = attempt(url, req, part, progress)) {
                is Attempt.Finished -> {
                    if (!moveIntoPlace(part, target)) {
                        return@withContext SaveOutcome.Failed(RecordingProblem.UNKNOWN, offline = false, writtenBytes = part.length(), rangeSupported = true)
                    }
                    return@withContext SaveOutcome.Done(target.length())
                }
                is Attempt.Stopped -> {
                    val f = r.failure
                    // Out of space mid-save after bytes were written: keep what is there, as a playable
                    // truncated file under the final name. (needBytes != null is a refusal of a KNOWN size
                    // before any download: nothing new to keep.) No other link would help either way.
                    if (f.problem == RecordingProblem.NO_SPACE && f.needBytes == null && part.length() > 0L) {
                        if (target.exists()) target.delete()
                        if (moveIntoPlace(part, target)) {
                            ResumeMeta.delete(part)
                            return@withContext SaveOutcome.Done(target.length(), stoppedFor = RecordingProblem.NO_SPACE)
                        }
                    }
                    return@withContext f
                }
                is Attempt.Failure -> {
                    val f = r.failure
                    if (best == null || f.writtenBytes >= best.writtenBytes) best = f
                    // Offline: every other link fails the same way.
                    if (f.offline) break
                }
            }
        }
        best ?: SaveOutcome.Failed(RecordingProblem.NO_CATCH_UP, offline = false, writtenBytes = part.length(), rangeSupported = false)
    }

    private fun moveIntoPlace(part: File, target: File): Boolean = try {
        try {
            java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (_: IOException) {
        false
    }

    private sealed interface Attempt {
        data object Finished : Attempt
        data class Failure(val failure: SaveOutcome.Failed) : Attempt
        data class Stopped(val failure: SaveOutcome.Failed) : Attempt
    }

    private suspend fun attempt(url: String, req: SaveRequest, part: File, progress: (Long) -> Unit): Attempt {
        // Resume only a partial known to come from THIS link; anything else would splice another file's bytes in.
        val meta = ResumeMeta.read(part)
        var offset = if (req.resumeFromBytes > 0 && meta?.url == url) part.length() else 0L
        if (offset == 0L) { part.delete(); ResumeMeta.delete(part) }
        var restartedOnce = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val request = try {
                HttpRequest.newBuilder(URI(url))
                    .timeout(Duration.ofMillis(stallMs))
                    .apply {
                        req.headers.forEach { (k, v) ->
                            if (k.isNotBlank() && v.isNotBlank() &&
                                !k.equals("Range", true) && !k.equals("If-Range", true) && !k.equals("Accept-Encoding", true)
                            ) {
                                header(k, v)
                            }
                        }
                        header("Accept-Encoding", "identity")
                        if (offset > 0L) {
                            header("Range", "bytes=$offset-")
                            meta?.validator?.let { header("If-Range", it) }
                        }
                    }
                    .GET()
                    .build()
            } catch (e: IllegalArgumentException) {
                // A link the client cannot parse (or a header it forbids) is a link the provider can't serve.
                return Attempt.Failure(SaveOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, false, part.length(), false))
            } catch (e: java.net.URISyntaxException) {
                return Attempt.Failure(SaveOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, false, part.length(), false))
            }
            val stall = RecordingStallPolicy(0, stallMs)
            val clock = TimeSource.Monotonic.markNow()
            var stalledByWatchdog = false
            // The open body, so the watchdog and a cancellation can close it and unblock a read.
            var open: InputStream? = null
            try {
                val outcome = coroutineScope {
                    // A blocked read cannot see cancellation: when this scope is cancelled, close the body to unblock it.
                    val closer = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            runCatching { open?.close() }
                        }
                    }
                    val watchdog = launch {
                        while (true) {
                            delay(watchdogTickMs)
                            val stalled = synchronized(stall) { stall.isStalled(clock.elapsedNow().inWholeMilliseconds) }
                            if (stalled) {
                                stalledByWatchdog = true
                                runCatching { open?.close() }
                                break
                            }
                        }
                    }
                    try {
                        val response = send(request)
                        if (response.statusCode() == 416 && offset > 0L && !restartedOnce) {
                            runCatching { response.body().close() }
                            part.delete(); ResumeMeta.delete(part); offset = 0L; restartedOnce = true
                            return@coroutineScope null // go round again from zero
                        }
                        if (response.statusCode() == 206 && offset > 0L &&
                            response.headers().firstValue("Content-Range").orElse(null)?.let(::startOfRange) != offset
                        ) {
                            // Bytes that do not start where our partial ends: never append them.
                            runCatching { response.body().close() }
                            part.delete(); ResumeMeta.delete(part); offset = 0L
                            return@coroutineScope null
                        }
                        val input = response.body()
                        open = input
                        input.use {
                            if (response.statusCode() !in 200..299) {
                                val kind = RecordingSaveClassifier.forHttp(response.statusCode(), isOnline())
                                return@coroutineScope Attempt.Failure(SaveOutcome.Failed(kind.problem, kind.offline, part.length(), false))
                            }
                            val contentRange = response.headers().firstValue("Content-Range").orElse(null)
                            val resumed = response.statusCode() == 206 && offset > 0L && contentRange?.let(::startOfRange) == offset
                            val rangeSupported = resumed ||
                                response.headers().firstValue("Accept-Ranges").orElse(null)?.contains("bytes", true) == true
                            val length = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()?.takeIf { it >= 0 }
                            val expected = length?.let { if (resumed) offset + it else it }
                            if (!resumed) {
                                ResumeMeta.write(
                                    part, url,
                                    response.headers().firstValue("ETag").orElse(null),
                                    response.headers().firstValue("Last-Modified").orElse(null),
                                )
                            }
                            // A declared size that will not fit is refused now, before a body byte is written.
                            if (expected != null) {
                                val onDisk = if (resumed) offset else 0L
                                val verdict = req.onKnownTotal?.invoke(expected, onDisk)
                                if (verdict is KnownSizeDecision.Refuse) {
                                    return@coroutineScope Attempt.Stopped(
                                        SaveOutcome.Failed(
                                            RecordingProblem.NO_SPACE, false, part.length(), rangeSupported,
                                            needBytes = verdict.needBytes, freeBytes = verdict.freeBytes,
                                        ),
                                    )
                                }
                            }
                            var written = if (resumed) offset else 0L
                            var sinceCheck = 0L
                            progress(written)
                            // The bytes are about to start: the clock for "no progress" starts now.
                            synchronized(stall) { stall.onProgress(clock.elapsedNow().inWholeMilliseconds) }
                            FileOutputStream(part, resumed).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer)
                                    if (n == -1) break
                                    out.write(buffer, 0, n)
                                    written += n
                                    sinceCheck += n
                                    synchronized(stall) { stall.onProgress(clock.elapsedNow().inWholeMilliseconds) }
                                    progress(written)
                                    if (sinceCheck >= SPACE_CHECK_BYTES) {
                                        sinceCheck = 0
                                        val problem = req.spaceCheck?.invoke(SPACE_CHECK_BYTES)
                                        if (problem != null) {
                                            return@coroutineScope Attempt.Stopped(SaveOutcome.Failed(problem, false, written, rangeSupported))
                                        }
                                    }
                                }
                                out.fd.sync()
                            }
                            when {
                                written == 0L -> Attempt.Failure(SaveOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, false, 0, rangeSupported))
                                (expected != null && written != expected) ||
                                    RecordingCompletenessPolicy.endedTooEarly(written, expected, req.estimatedBytes) -> {
                                    val kind = RecordingSaveClassifier.forIo(null, stalled = true, online = isOnline())
                                    Attempt.Failure(SaveOutcome.Failed(kind.problem, kind.offline, written, rangeSupported))
                                }
                                else -> { ResumeMeta.delete(part); Attempt.Finished }
                            }
                        }
                    } finally {
                        watchdog.cancel()
                        closer.cancel()
                    }
                }
                return outcome ?: continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // A closed body after our own cancellation is a cancellation, not a failure.
                currentCoroutineContext().ensureActive()
                val kind = RecordingSaveClassifier.forIo(e, stalledByWatchdog, isOnline())
                return Attempt.Failure(SaveOutcome.Failed(kind.problem, kind.offline, part.length(), offset > 0L || part.length() > 0))
            }
        }
    }

    /** The request, cancellable: cancelling the coroutine cancels the in-flight exchange. */
    private suspend fun send(request: HttpRequest): HttpResponse<InputStream> =
        suspendCancellableCoroutine { cont ->
            val future: CompletableFuture<HttpResponse<InputStream>> =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            cont.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { value, error ->
                if (error != null) {
                    // CompletionException wraps the real cause.
                    cont.resumeWith(Result.failure((error as? java.util.concurrent.CompletionException)?.cause ?: error))
                } else {
                    cont.resumeWith(Result.success(value))
                }
            }
        }

    private fun startOfRange(header: String): Long? =
        Regex("bytes (\\d+)-").find(header.trim())?.groupValues?.get(1)?.toLongOrNull()

    internal companion object {
        const val WATCHDOG_TICK_MS = 5_000L
        const val SPACE_CHECK_BYTES = 16L * 1024 * 1024

        private val defaultClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        /** The JVM has no reachability API: any up, non-loopback interface with an address counts as "a network". Errs to online. */
        fun hasUsableNetwork(): Boolean = try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.any {
                it.isUp && !it.isLoopback && it.inetAddresses.hasMoreElements()
            } ?: true
        } catch (_: Exception) {
            true
        }
    }
}

/**
 * Where a `.part` came from: the link, and the validators that link answered with, stored beside it as
 * `<part>.meta`. A resume is only tried against the same link, conditioned on the validator with
 * If-Range. Missing or unreadable = unknown source = no resume.
 */
internal data class ResumeMeta(val url: String, val etag: String?, val lastModified: String?) {
    /** The strong ETag when there is one, else the Last-Modified date. */
    val validator: String? get() = etag?.takeIf { !it.startsWith("W/") } ?: lastModified

    companion object {
        private fun file(part: File) = File(part.path + ".meta")

        fun write(part: File, url: String, etag: String?, lastModified: String?) {
            val text = listOf(url, etag.orEmpty(), lastModified.orEmpty()).joinToString("\n")
            val tmp = File(part.path + ".meta.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file(part))) { file(part).writeText(text); tmp.delete() }
        }

        fun read(part: File): ResumeMeta? {
            val lines = try { file(part).takeIf { it.isFile }?.readText()?.split("\n") } catch (e: IOException) { null } ?: return null
            val url = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            return ResumeMeta(url, lines.getOrNull(1)?.takeIf { it.isNotBlank() }, lines.getOrNull(2)?.takeIf { it.isNotBlank() })
        }

        fun delete(part: File) { file(part).delete() }
    }
}
