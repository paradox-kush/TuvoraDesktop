package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.api.TelemetryHost
import com.nuvio.app.core.telemetry.internal.CrashRecordCodec
import com.nuvio.app.core.telemetry.internal.CrashReporter
import com.nuvio.app.core.telemetry.internal.DefaultTelemetryPort
import com.nuvio.app.core.telemetry.internal.DiagTrailPolicy
import com.nuvio.app.core.telemetry.internal.ReportRateLimit
import com.nuvio.app.core.telemetry.internal.SendDecision
import com.nuvio.app.core.telemetry.internal.SupportCodeGenerator
import com.nuvio.app.core.telemetry.internal.TelemetrySendPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeHost(var consent: Boolean = true, var now: Long = 1_000_000L) : TelemetryHost {
    val files = HashMap<String, String>()
    val captured = ArrayList<Triple<String, Map<String, Any>, Long?>>()
    private var seed = 7
    override fun nowMs() = now
    override fun secureRandomInt(bound: Int): Int { seed = (seed * 31 + 11) and 0x7fffffff; return seed % bound }
    override fun readFile(name: String) = files[name]
    override fun writeFileAtomic(name: String, text: String): Boolean { files[name] = text; return true }
    override fun deleteFile(name: String) { files.remove(name) }
    override fun consentGranted() = consent
    override fun memorySample(): Map<String, Any> = mapOf("used_mb" to 100L, "max_mb" to 1024L)
    override fun hardwareProfile(): Map<String, Any> = mapOf("hw_cpu_cores" to 8)
    override fun appVersion() = "9.9.9"
    override fun capture(event: String, props: Map<String, Any>, atMs: Long?) { captured += Triple(event, props, atMs) }
    override fun flush() = Unit
}

class TelemetryPoliciesTest {
    @Test
    fun supportCodeHasFormatAlphabetAndIsStableOnceStored() {
        val host = FakeHost()
        val p1 = DefaultTelemetryPort(host, CoroutineScope(Dispatchers.Unconfined))
        val code = p1.supportCode
        assertEquals(8, code.length)
        assertTrue(code.all { it in SupportCodeGenerator.ALPHABET })
        assertFalse(code.any { it in "01OIL" })
        assertEquals(code, DefaultTelemetryPort(host, CoroutineScope(Dispatchers.Unconfined)).supportCode)
        assertEquals(9, SupportCodeGenerator.display(code).length)
        assertEquals('-', SupportCodeGenerator.display(code)[4])
    }

    @Test
    fun lookupToleratesCaseDashAndLookAlikes() {
        assertEquals(SupportCodeGenerator.lookupKey("K7Q2-9MPX"), SupportCodeGenerator.lookupKey("k7q29mpx"))
        assertEquals(SupportCodeGenerator.lookupKey("K7Q2-9MPX"), SupportCodeGenerator.lookupKey(" k7q2 9mpx "))
        assertEquals(SupportCodeGenerator.lookupKey("AB1C"), SupportCodeGenerator.lookupKey("abIc"))
    }

    @Test
    fun trailDropsUrlsHostsNamesAndUnlistedFields() {
        val e = DiagEntry(
            1L, DiagArea.PLAYBACK, "open_failed",
            mapOf(
                "engine" to "mpv", "attempt" to 2,
                "url" to "http://panel.example.com/live/u/p/1.ts",
                "reason" to "http://panel.example.com/x",
                "outcome" to "box.example.tv",
                "channel_name" to "BBC One",
                "note" to "free text with spaces",
            ),
        )
        val clean = assertNotNull(DiagTrailPolicy.sanitize(e))
        assertEquals(mapOf("engine" to "mpv", "attempt" to 2), clean.fields)
        assertNull(DiagTrailPolicy.sanitize(e.copy(code = "http://x.com/a")))
    }

    @Test
    fun trailIsBoundedInEntriesAndBytes() {
        val many = (1..500).map { DiagEntry(it.toLong(), DiagArea.PLAYBACK, "step_$it", mapOf("ms" to it)) }
        val json = DiagTrailPolicy.trailJson(many)
        assertTrue(json.length <= DiagTrailPolicy.MAX_BYTES)
        assertTrue(json.contains("step_500"))
        assertFalse(json.contains("\"step_1\""))
        assertTrue(DiagTrailPolicy.trailJson(many, maxBytes = 300).length <= 300)
    }

    @Test
    fun sendPolicyPerEventClass() {
        fun d(e: String, vararg p: Pair<String, Any>) = TelemetrySendPolicy.decide(e, mapOf(*p))
        assertEquals(SendDecision.RECORD_ONLY, d("clean_playback_diagnostic"))
        assertEquals(SendDecision.RECORD_ONLY, d("\$screen"))
        assertEquals(SendDecision.COUNT, d("iptv_load", "outcome" to "loaded"))
        assertEquals(SendDecision.SEND, d("iptv_load", "outcome" to "timeout"))
        assertEquals(SendDecision.COUNT, d("epg_ingest", "outcome" to "ok"))
        assertEquals(SendDecision.SEND, d("epg_ingest", "outcome" to "failed"))
        assertEquals(SendDecision.COUNT, d("epg_resolve", "outcome" to "ok"))
        assertEquals(SendDecision.SEND, d("app_exit", "reason" to "crash"))
        assertEquals(SendDecision.COUNT, d("app_exit", "reason" to "low_memory", "importance" to "cached"))
        assertEquals(SendDecision.SEND, d("live_playback_freeze"))
    }

    @Test
    fun rateLimitIsOnePerTwoMinutes() {
        val r = ReportRateLimit()
        assertNull(r.retryInSec(0))
        r.markSent(0)
        assertEquals(120, r.retryInSec(0))
        assertEquals(1, r.retryInSec(119_001))
        assertNull(r.retryInSec(120_000))
    }

    @Test
    fun crashRecordRoundTripsAndStackCarriesNoMessage() {
        val err = IllegalStateException("GET http://panel.example.com/live?token=abc failed", RuntimeException("user=bob"))
        val rec = com.nuvio.app.core.telemetry.internal.CrashRecord(
            "id-1", "K7Q29MPX", "1.0", 42L, "main", CrashRecordCodec.typeOf(err),
            CrashRecordCodec.stackFrom(err),
            listOf(DiagEntry(1, DiagArea.SCREEN, "enter", mapOf("screen" to "Home"))),
            mapOf("used_mb" to 5L), "Home",
        )
        val text = CrashRecordCodec.encode(rec)
        assertFalse(text.contains("panel.example.com"))
        assertFalse(text.contains("bob"))
        val back = assertNotNull(CrashRecordCodec.decode(text))
        assertEquals("id-1", back.crashId)
        assertEquals(42L, back.atMs)
        assertEquals(rec.stack, back.stack)
        assertTrue(back.stack.any { it.startsWith("Caused by: ") })
        assertTrue(back.stack.size <= CrashRecordCodec.MAX_FRAMES)
        assertEquals("Home", back.screen)
        assertNull(CrashRecordCodec.decode("{not json"))
    }

    /** Regression for the old gap: a Java crash left no stack. Crash -> file -> next start sends one report. */
    @Test
    fun uncaughtCrashIsSentOnceWithStackAndCrashTime() {
        val host = FakeHost(now = 5_000L)
        val port = DefaultTelemetryPort(host, CoroutineScope(Dispatchers.Unconfined))
        port.record(DiagEntry(4_000L, DiagArea.SCREEN, "enter", mapOf("screen" to "LiveTv")))
        assertTrue(CrashReporter.writePending(host, port.supportCode, port.flightRecorder.lastPublished(), "AWT-EventQueue-0", IllegalStateException("boom"), "crash-1"))
        assertTrue(host.files.containsKey(CrashReporter.PENDING))

        host.now = 900_000L // next launch, much later
        assertEquals(1, CrashReporter.sendPending(host))
        val (event, props, at) = host.captured.single()
        assertEquals("app_crash_report", event)
        assertEquals(5_000L, at)
        assertEquals("crash-1", props["crash_id"])
        assertTrue((props["stack"] as String).contains("IllegalStateException"))
        assertEquals("LiveTv", props["screen"])
        assertFalse(host.files.containsKey(CrashReporter.PENDING))
        assertFalse(host.files.containsKey(CrashReporter.SENDING))
        assertEquals(0, CrashReporter.sendPending(host)) // exactly once
    }

    @Test
    fun leftoverSendingFileIsResentWithSameId() {
        val host = FakeHost()
        val port = DefaultTelemetryPort(host, CoroutineScope(Dispatchers.Unconfined))
        CrashReporter.writePending(host, port.supportCode, emptyList(), "t", RuntimeException("x"), "crash-2")
        host.files[CrashReporter.SENDING] = host.files.remove(CrashReporter.PENDING)!!
        assertEquals(1, CrashReporter.sendPending(host))
        assertEquals("crash-2", host.captured.single().second["crash_id"])
    }

    @Test
    fun consentOffSendsNothingAndDeletesTheRecord() = runTest {
        val host = FakeHost(consent = false)
        val port = DefaultTelemetryPort(host, CoroutineScope(Dispatchers.Unconfined))
        CrashReporter.writePending(host, port.supportCode, emptyList(), "t", RuntimeException("x"), "crash-3")
        assertEquals(0, CrashReporter.sendPending(host))
        assertFalse(host.files.containsKey(CrashReporter.PENDING))
        assertEquals(ReportResult.ConsentOff, port.sendUserReport(ReportCategory.OTHER, ReportSurface.SETTINGS))
        port.reportError("live_playback_freeze", mapOf("x" to 1))
        port.sendSessionHealth()
        assertTrue(host.captured.isEmpty())
    }

    @Test
    fun userReportIsSentThenRateLimited() = runTest {
        val host = FakeHost()
        val port = DefaultTelemetryPort(host, CoroutineScope(Dispatchers.Unconfined))
        port.record(DiagEntry(1, DiagArea.PLAYBACK, "player_error", mapOf("surface" to "live")))
        val first = port.sendUserReport(ReportCategory.WONT_PLAY, ReportSurface.LIVE)
        assertEquals(ReportResult.Sent(port.supportCode), first)
        val props = host.captured.single { it.first == "user_issue_report" }.second
        assertEquals("wont_play", props["category"])
        assertTrue((props["diag_trail"] as String).contains("player_error"))
        assertTrue(props.containsKey("report_id"))
        assertTrue(port.sendUserReport(ReportCategory.OTHER, ReportSurface.LIVE) is ReportResult.RateLimited)
        assertEquals(1, host.captured.count { it.first == "user_issue_report" })
    }

    @Test
    fun errorsCarryTheTrailAndCountedEventsGoToSessionHealth() {
        val host = FakeHost()
        val port = DefaultTelemetryPort(host, CoroutineScope(Dispatchers.Unconfined))
        port.record(DiagEntry(1, DiagArea.PLAYBACK, "open", mapOf("engine" to "mpv")))
        assertFalse(port.route("clean_playback_diagnostic", mapOf("phase" to "open")))
        assertFalse(port.route("iptv_load", mapOf("surface" to "live", "outcome" to "loaded")))
        assertTrue(port.route("iptv_load", mapOf("surface" to "live", "outcome" to "failed")))
        port.reportError("live_playback_freeze", mapOf("engine" to "mpv"))
        val err = host.captured.single { it.first == "live_playback_freeze" }.second
        assertTrue((err["diag_trail"] as String).contains("open"))
        port.sendSessionHealth()
        val health = host.captured.single { it.first == "session_health" }.second
        assertEquals(1, health["n.iptv_load.live.loaded"])
    }
}
