package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.MemoryContext
import com.nuvio.app.core.telemetry.internal.TelemetryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackTelemetryServiceTest {
    private fun newService(host: FakeTelemetryHost) = TelemetryService(host, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `routine successes only increment session_health counts and emit no event`() {
        val host = FakeTelemetryHost()
        val s = newService(host)
        s.count("ttff:live:libmpv:lt1000")
        s.count("ttff:live:libmpv:lt1000")
        s.count("teardown:libmpv:clean")
        s.count("stall:live:recovered")
        assertTrue(host.sent.isEmpty(), "nothing is sent while counting: ${host.sent.map { it.event }}")
        s.onBackground()
        assertEquals(1, host.sent.count { it.event == "session_health" }, "one summary")
        assertTrue(host.sent.none { it.event.startsWith("ttff") || it.event.startsWith("stall") || it.event.startsWith("teardown") }, "no per-start events")
        val health = host.sent.single().props
        assertEquals(2, health["n_ttff:live:libmpv:lt1000"])
        assertEquals(1, health["n_teardown:libmpv:clean"])
        assertEquals(1, health["n_stall:live:recovered"])
    }

    @Test
    fun `a key outside the closed vocabulary is ignored`() {
        val host = FakeTelemetryHost()
        val s = newService(host)
        s.count("ttff:live:libmpv:lt999")
        s.count("channel:Some Channel")
        s.count("ttff:live:libmpv:lt250", by = 0)
        s.onBackground()
        assertTrue(host.sent.none { it.event == "session_health" }, "nothing valid was counted")
    }

    @Test
    fun `with the switch off nothing is counted or sent`() {
        val host = FakeTelemetryHost(consent = false)
        val s = newService(host)
        s.count("ttff:live:libmpv:lt1000")
        s.count("teardown:libmpv:failed")
        s.sampleMemoryOnce()
        s.onBackground()
        assertTrue(host.sent.isEmpty(), "sent: ${host.sent.map { it.event }}")
        assertFalse(MemoryContext.FILE in host.store.files, "no memory file is kept while reports are off")
        // Turning it on later starts from zero: what happened while off was never kept.
        host.consent = true
        s.count("ttff:live:libmpv:lt250")
        s.onBackground()
        val health = host.sent.single { it.event == "session_health" }.props
        assertEquals(1, health["n_ttff:live:libmpv:lt250"])
        assertFalse(health.containsKey("n_ttff:live:libmpv:lt1000"), "the count from while off was dropped")
    }

    @Test
    fun `app_exit carries the previous run's last memory sample and nothing else does`() {
        val host = FakeTelemetryHost()
        newService(host).sampleMemoryOnce()
        assertTrue(MemoryContext.FILE in host.store.files)

        val next = newService(host)
        next.emit("app_exit", mapOf("reason" to "crash"))
        val exit = host.sent.single { it.event == "app_exit" }.props
        assertEquals(120L, exit["mem_java_mb"])
        assertEquals(410L, exit["mem_pss_mb"])

        next.emit("iptv_load", mapOf("surface" to "hub_categories", "outcome" to "timeout"))
        val other = host.sent.single { it.event == "iptv_load" }.props
        assertFalse(other.keys.any { it.startsWith("mem_") }, "only app_exit gets the previous sample")
    }

    @Test
    fun `an app_exit that already has memory keeps its own`() {
        val host = FakeTelemetryHost()
        newService(host).sampleMemoryOnce()
        val next = newService(host)
        next.emit("app_exit", mapOf("reason" to "crash", "mem_java_mb" to 7L))
        assertEquals(7L, host.sent.single { it.event == "app_exit" }.props["mem_java_mb"])
    }

    @Test
    fun `memory context survives only its closed field names`() {
        val text = MemoryContext.encode(mapOf("java_mb" to 5L, "stream_host" to 9L, "threads" to 12L))
        assertEquals(mapOf("java_mb" to 5L, "threads" to 12L), MemoryContext.decode(text))
        assertEquals(emptyMap(), MemoryContext.decode("not json"))
        assertEquals(emptyMap(), MemoryContext.decode(null))
        assertEquals(emptyMap(), MemoryContext.decode("{\"java_mb\":\"http://x\"}"))
    }
}
