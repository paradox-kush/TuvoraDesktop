package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.analytics.PostHogPrivacy
import com.posthog.PostHog
import com.posthog.internal.PostHogMemoryPreferences
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the REAL PostHog JVM SDK (same config builder as production) against a localhost server that stands in for
 * the PostHog host. Nothing here ever reaches the real PostHog. The SDK is a process singleton, so every test
 * closes it in [tearDown] and the tests run one after another in this single class.
 */
class DesktopTelemetryDeliveryTest {
    private val apiKey = "phc_test_key_not_real"
    private lateinit var queueRoot: Path
    private var server: FakeHost? = null

    @BeforeTest
    fun setUp() {
        queueRoot = Files.createTempDirectory("tuvora-posthog-queue").resolve("posthog-queue")
        DesktopSyncSender.shuttingDown = false
    }

    @AfterTest
    fun tearDown() {
        runCatching { PostHog.close() }
        server?.stop()
        DesktopSyncSender.shuttingDown = false
        DesktopSyncSender.endpoint = "https://us.i.posthog.com/batch/"
    }

    private class FakeHost(private val delayMs: Long = 0) {
        @Volatile var status = 200

        class Request(val path: String, val body: String)

        val requests = CopyOnWriteArrayList<Request>()
        private val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url: String get() = "http://127.0.0.1:${http.address.port}"

        init {
            http.createContext("/") { ex ->
                val raw = ex.requestBody.readBytes()
                val bytes = if (ex.requestHeaders.getFirst("Content-Encoding")?.contains("gzip") == true) {
                    GZIPInputStream(raw.inputStream()).readBytes()
                } else raw
                requests += Request(ex.requestURI.path, String(bytes, Charsets.UTF_8))
                if (delayMs > 0) Thread.sleep(delayMs)
                val reply = """{"status":1}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, reply.size.toLong())
                ex.responseBody.use { it.write(reply) }
            }
            http.executor = java.util.concurrent.Executors.newCachedThreadPool()
            http.start()
        }

        fun batchesWith(event: String): List<Request> =
            requests.filter { it.path.startsWith("/batch") && it.body.contains("\"$event\"") }

        fun stop() = http.stop(0)
    }

    private fun setup(host: String, enabled: Boolean = true) {
        val config = DesktopPostHogConfig.build(apiKey, host, PostHogMemoryPreferences(), enabled, "test", queueRoot)
        PostHog.setup(config)
        if (enabled) PostHog.optIn() else PostHog.optOut()
    }

    private fun awaitTrue(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    private fun queueFiles(): List<Path> {
        val dir = DesktopTelemetryQueue.dir(queueRoot, apiKey)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
    }

    @Test
    fun aCapturedUserReportReachesTheHost() {
        val host = FakeHost().also { server = it }
        setup(host.url)
        PostHog.capture("user_issue_report", properties = mapOf("report_id" to "r1"))
        PostHog.flush()
        assertTrue(
            awaitTrue { host.batchesWith("user_issue_report").isNotEmpty() },
            "a /batch request carrying user_issue_report must arrive; requests seen: ${host.requests.map { it.path }}",
        )
    }

    @Test
    fun theProductionConfigGivesTheSdkAQueueDirectory() {
        val config = DesktopPostHogConfig.build(apiKey, "http://127.0.0.1:1", PostHogMemoryPreferences(), true, "test", queueRoot)
        assertEquals(queueRoot.toString(), config.storagePrefix, "without storagePrefix the SDK drops every event")
    }

    @Test
    fun anEventQueuedWhileTheHostWasUnreachableIsDeliveredAfterARestart() {
        // Launch 1: the host answers 503 (a retriable failure), so the event stays in the on-disk queue.
        // (A refused connection would NOT do: the SDK treats only DNS/timeout failures as keep-and-retry.)
        val host = FakeHost().also { server = it }
        host.status = 503
        setup(host.url)
        PostHog.capture("app_crash_report", properties = mapOf("crash_id" to "c1"))
        PostHog.flush()
        assertTrue(awaitTrue(5_000) { host.batchesWith("app_crash_report").isNotEmpty() }, "launch 1 must try to send")
        assertTrue(queueFiles().isNotEmpty(), "an unsent event must be persisted to disk")
        PostHog.close()
        host.requests.clear()

        // Launch 2: the host is healthy again; the leftover file must be replayed.
        host.status = 200
        setup(host.url)
        PostHog.flush()
        assertTrue(
            awaitTrue { host.batchesWith("app_crash_report").isNotEmpty() },
            "the leftover queue file must be delivered on the next launch",
        )
        assertTrue(awaitTrue(5_000) { queueFiles().isEmpty() }, "a delivered file is removed from the queue")
    }

    @Test
    fun anOptedOutInstallWritesNothingAndSendsNothing() {
        val host = FakeHost().also { server = it }
        setup(host.url, enabled = false)
        repeat(3) { PostHog.capture("user_issue_report", properties = mapOf("n" to it)) }
        PostHog.captureException(RuntimeException("boom"))
        PostHog.flush()
        Thread.sleep(1_000)
        assertFalse(Files.exists(queueRoot), "no queue directory may be created for an opted-out install")
        assertEquals(emptyList(), host.requests.filter { it.path.startsWith("/batch") }.map { it.path }, "nothing may be uploaded")
    }

    @Test
    fun theExitHookDeliversSessionHealthBeforeItReturnsEvenWhenTheHostIsSlow() {
        val host = FakeHost(delayMs = 500).also { server = it }
        DesktopSyncSender.endpoint = host.url + "/batch/"
        setup(host.url)
        val started = System.currentTimeMillis()
        DesktopExitHook.run(
            sendHealthNow = {
                DesktopSyncSender.send("session_health", mapOf("n_iptv_load" to 3), emptyMap(), "install-1", null)
            },
            finalize = {},
            flush = { PostHog.flush() },
            close = { PostHog.close() },
        )
        val elapsed = System.currentTimeMillis() - started
        assertEquals(1, host.batchesWith("session_health").size, "the exit hook must have delivered session_health when it returns")
        assertTrue(elapsed < 5_000, "the exit hook stays bounded, took $elapsed ms")
    }

    @Test
    fun theExitHookStillClosesWhenTheHealthSendThrows() {
        val host = FakeHost().also { server = it }
        setup(host.url)
        var closed = false
        DesktopExitHook.run(
            sendHealthNow = { throw IllegalStateException("send exploded") },
            finalize = { throw IllegalStateException("finalize exploded") },
            flush = {},
            close = { closed = true },
            graceMs = 10,
        )
        assertTrue(closed, "a failing health send must never keep the process from shutting down")
        assertTrue(DesktopSyncSender.shuttingDown, "shuttingDown stays set for the exit path")
    }

    // --- backlog policy ----------------------------------------------------------------------------------

    private val hour = 3_600_000L
    private val now = 1_000_000_000_000L

    @Test
    fun anEmptyQueueHasNoBacklog() {
        assertEquals(TelemetryQueueBacklog(0, 0), TelemetryQueueBacklogPolicy.evaluate(emptyList(), now))
    }

    @Test
    fun backlogCountsFilesOlderThanADayAndReportsTheOldestInHours() {
        val ages = listOf(2 * hour, 25 * hour, 50 * hour + 1_000, 24 * hour)
        val result = TelemetryQueueBacklogPolicy.evaluate(ages.map { now - it }, now)
        assertEquals(TelemetryQueueBacklog(backlog = 2, oldestHours = 50), result, "exactly 24 h old is not yet stale")
    }

    @Test
    fun aFileFromTheFutureCountsAsFreshNotNegative() {
        val result = TelemetryQueueBacklogPolicy.evaluate(listOf(now + 5 * hour), now)
        assertEquals(TelemetryQueueBacklog(0, 0), result)
    }

    @Test
    fun theBacklogPropsAreNumbersUnderNamesThePrivacyGuardKeeps() {
        val props = TelemetryQueueBacklog(3, 70).asProps()
        assertEquals(setOf("tele_queue_backlog", "tele_queue_oldest_h"), props.keys)
        assertTrue(props.values.all { it is Int })
        assertEquals(props.keys, PostHogPrivacy.sanitize(props).keys - PostHogPrivacy.GEOIP_DISABLE_PROPERTY)
    }

    @Test
    fun scanReadsTheQueueDirectoryAgesAndNeverThrowsWithoutOne() {
        assertEquals(TelemetryQueueBacklog(0, 0), DesktopTelemetryQueue.scan(queueRoot, apiKey, now), "no directory, no backlog")
        val dir = Files.createDirectories(DesktopTelemetryQueue.dir(queueRoot, apiKey))
        for ((name, age) in listOf("a.event" to 30 * hour, "b.event" to 3 * hour)) {
            val f = Files.createFile(dir.resolve(name))
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(now - age))
        }
        assertEquals(TelemetryQueueBacklog(1, 30), DesktopTelemetryQueue.scan(queueRoot, apiKey, now))
    }

    @Test
    fun purgeRemovesQueuedEventsAndToleratesAMissingDirectory() {
        DesktopTelemetryQueue.purge(queueRoot, apiKey) // nothing there: must not throw
        val dir = Files.createDirectories(DesktopTelemetryQueue.dir(queueRoot, apiKey))
        Files.createFile(dir.resolve("x.event"))
        DesktopTelemetryQueue.purge(queueRoot, apiKey)
        assertEquals(emptyList(), queueFiles(), "opting out must not leave events behind to be uploaded later")
    }
}
