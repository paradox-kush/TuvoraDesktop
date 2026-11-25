package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.PlaybackTelemetry
import com.nuvio.app.core.telemetry.api.QaMirror
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QaMirrorTest {
    private val lines = ArrayList<String>()

    @AfterTest
    fun reset() = QaMirror.resetForTest()

    @Test
    fun `the switch is exactly 1 from either source`() {
        assertTrue(QaMirror.enabledBy("1"))
        assertTrue(QaMirror.enabledBy(null, "1"))
        assertTrue(QaMirror.enabledBy(" 1 ", null))
        assertFalse(QaMirror.enabledBy(null, null))
        assertFalse(QaMirror.enabledBy("0", "true"))
        assertFalse(QaMirror.enabledBy("", ""))
    }

    @Test
    fun `off by default and costs nothing when off`() {
        var built = false
        QaMirror.mirror("ttff") { built = true; mapOf("ms" to 1L) }
        assertFalse(built, "the field map is not even built while off")
        PlaybackTelemetry.ttff("live", "libmpv", 1_840L, RecordingTelemetryPort { _, _ -> })
        assertTrue(lines.isEmpty())
        QaMirror.configure(enabled = false) { lines += it }
        QaMirror.mirror("ttff") { mapOf("ms" to 1L) }
        assertTrue(lines.isEmpty() && !QaMirror.isOn)
    }

    @Test
    fun `on, a signal is one TuvoraQa json line with exact values`() {
        QaMirror.configure(enabled = true) { lines += it }
        val port = RecordingTelemetryPort { _, _ -> }
        PlaybackTelemetry.ttff("live", "libmpv", 1_840L, port)
        assertEquals(listOf("TuvoraQa {\"ev\":\"ttff\",\"kind\":\"live\",\"engine\":\"libmpv\",\"ms\":1840}"), lines)
        assertEquals(listOf("ttff:live:libmpv:lt2000"), port.counts, "the network side still only sees the bucket")
    }

    @Test
    fun `stall and teardown mirror exact milliseconds and outcomes`() {
        QaMirror.configure(enabled = true) { lines += it }
        val port = RecordingTelemetryPort { _, _ -> }
        PlaybackTelemetry.stall("live", recovered = false, ms = 12_345L, port = port)
        PlaybackTelemetry.teardown("libmpv", ms = 2_100L, failed = false, port = port)
        assertEquals("TuvoraQa {\"ev\":\"stall\",\"kind\":\"live\",\"outcome\":\"gave_up\",\"ms\":12345}", lines[0])
        assertEquals("TuvoraQa {\"ev\":\"teardown\",\"engine\":\"libmpv\",\"outcome\":\"slow\",\"ms\":2100}", lines[1])
        assertEquals(listOf("stall:live:gave_up", "stall_ms:live:lt15000", "teardown:libmpv:slow"), port.counts)
    }

    @Test
    fun `a line never carries a forbidden name or an unsafe value`() {
        val line = QaMirror.format(
            "ttff",
            mapOf("kind" to "live", "url" to "http://h/x", "host" to "h.example", "channel" to "Some Channel", "ms" to 5L, "ok" to true),
        )
        assertEquals("TuvoraQa {\"ev\":\"ttff\",\"kind\":\"live\",\"ms\":5,\"ok\":true}", line)
        assertNull(QaMirror.format("not a token!", mapOf("ms" to 1L)))
        assertNull(QaMirror.format("ttff\nTuvoraQa {}", emptyMap()))
    }

    @Test
    fun `an unknown kind counts and mirrors nothing`() {
        QaMirror.configure(enabled = true) { lines += it }
        val port = RecordingTelemetryPort { _, _ -> }
        PlaybackTelemetry.ttff("catchup", "libmpv", 100L, port)
        PlaybackTelemetry.stall("http://x", true, 100L, port)
        assertTrue(lines.isEmpty() && port.counts.isEmpty())
    }
}
