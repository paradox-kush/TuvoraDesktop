package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.PlaybackTelemetryBuckets
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryKeys
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryPrivacy
import com.nuvio.app.core.telemetry.internal.MemoryContext
import com.nuvio.app.core.telemetry.internal.SessionHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds every payload the playback telemetry can produce and fails on a forbidden key or a value outside its enum. */
class PlaybackTelemetryPrivacyTest {
    private val kinds = PlaybackTelemetryKeys.KINDS
    private val engines = PlaybackTelemetryKeys.ENGINES

    private fun allCountKeys(): List<String> = buildList {
        for (k in kinds) for (e in engines) for (ms in listOf(0L, 300L, 700L, 1_500L, 3_000L, 6_000L, 20_000L)) {
            PlaybackTelemetryKeys.ttff(k, e, ms)?.let(::add)
        }
        for (f in engines) for (t in engines) for (trigger in PlaybackTelemetryKeys.FALLBACK_TRIGGERS) add(PlaybackTelemetryKeys.fallback(f, t, trigger))
        for (k in kinds) {
            for (r in listOf(true, false)) PlaybackTelemetryKeys.stall(k, r)?.let(::add)
            for (ms in listOf(0L, 2_500L, 9_000L, 20_000L, 60_000L)) PlaybackTelemetryKeys.stallMs(k, ms)?.let(::add)
        }
        for (e in engines) for ((ms, failed) in listOf(10L to false, 2_000L to false, 10L to true)) add(PlaybackTelemetryKeys.teardown(e, ms, failed))
        for (ev in PlaybackTelemetryKeys.MULTIVIEW_EVENTS) PlaybackTelemetryKeys.multiview(ev)?.let(::add)
        for (n in 1..9) PlaybackTelemetryKeys.multiviewTilesMax(n)?.let(::add)
    }

    @Test
    fun `every count key the builders make is valid and short`() {
        for (key in allCountKeys()) {
            assertTrue(PlaybackTelemetryKeys.isValid(key), "invalid: $key")
            assertTrue(key.length <= PlaybackTelemetryKeys.MAX_KEY_LENGTH, "too long: $key")
        }
    }

    @Test
    fun `the session_health payload with every count key has no violation`() {
        val health = SessionHealth(0L, maxKeys = 10_000)
        allCountKeys().forEach { health.count(it) }
        val payload = health.snapshot(1L)!!.payload
        assertTrue(payload.keys.any { it.startsWith("n_ttff:") }, "colon keys survive the key sanitiser")
        assertEquals(emptyList(), PlaybackTelemetryPrivacy.violations(payload))
    }

    @Test
    fun `app_exit additions have no violation`() {
        val prev = buildList {
            add(PlaybackTelemetryKeys.previousRun(null, null, null))
            add(PlaybackTelemetryKeys.previousRun(false, "live", "libmpv"))
            for (k in kinds) for (e in listOf("media3", "libmpv", "avplayer", "mpv", "exo", "weird")) add(PlaybackTelemetryKeys.previousRun(true, k, e))
        }
        for (props in prev) assertEquals(emptyList(), PlaybackTelemetryPrivacy.violations(props), props.toString())
        val memory = MemoryContext.properties(
            mapOf("java_mb" to 120L, "java_max_mb" to 4_096L, "ram_free_mb" to 900L, "threads" to 80L, "fds" to 210L, "pss_mb" to 400L),
        )
        assertEquals(emptyList(), PlaybackTelemetryPrivacy.violations(memory))
    }

    @Test
    fun `diagnostic classes are all inside their enums`() {
        val props = linkedMapOf<String, Any>()
        for (mb in listOf(512L, 1_500L, 2_500L, 8_000L)) PlaybackTelemetryBuckets.ramClass(mb)?.let { props["ram_class"] = it; assertEquals(emptyList(), PlaybackTelemetryPrivacy.violations(props)) }
        for (h in listOf(null, 0, 480, 720, 1_080, 2_160)) {
            props["res_class"] = PlaybackTelemetryBuckets.resClass(h)
            assertEquals(emptyList(), PlaybackTelemetryPrivacy.violations(props))
        }
        for (s in listOf(null, 200, 302, 401, 404, 456, 418, 502)) {
            props["http_class"] = PlaybackTelemetryKeys.httpClass(s)
            assertEquals(emptyList(), PlaybackTelemetryPrivacy.violations(props))
        }
        for (u in listOf(null, "http://h/a.m3u8", "http://h/a.ts", "http://h/a")) {
            props["ext_class"] = PlaybackTelemetryKeys.extClass(u)
            assertEquals(emptyList(), PlaybackTelemetryPrivacy.violations(props))
        }
        for (c in listOf(null, "text/html", "video/mp2t", "application/json")) {
            props["content_type_class"] = PlaybackTelemetryKeys.contentTypeClass(c)
            assertEquals(emptyList(), PlaybackTelemetryPrivacy.violations(props))
        }
    }

    @Test
    fun `the validator catches what the contract forbids`() {
        for (name in listOf("code", "state", "url", "token", "host", "user", "password", "mac", "key", "stream_url", "api_key", "provider_host")) {
            assertTrue(PlaybackTelemetryPrivacy.violations(mapOf(name to 1)).isNotEmpty(), "property named $name")
        }
        assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("engine" to "ExoPlayer")).isNotEmpty(), "value outside the enum")
        assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("kind" to "http://provider.example/live/1.ts")).isNotEmpty())
        assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("channel_name" to "Some Channel")).isNotEmpty(), "string with no closed enum")
        assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("n_ttff:live:libmpv:lt999" to 1)).isNotEmpty(), "count key outside the vocabulary")
        assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("n_ttff:live:libmpv:lt250" to "1")).isNotEmpty(), "a count must be a number")
        assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("note" to "10.0.0.5"), freeStringKeys = emptySet()).isNotEmpty())
    }
}
