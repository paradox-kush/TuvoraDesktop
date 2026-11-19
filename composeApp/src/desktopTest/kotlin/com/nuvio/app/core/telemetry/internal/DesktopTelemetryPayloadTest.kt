package com.nuvio.app.core.telemetry.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopTelemetryPayloadTest {
    private val registered: Map<String, Any> = DesktopTelemetryPayload.staticSuperProperties(
        version = "0.17.0",
        osName = "Mac OS X",
        buildFlavor = "full",
        installSource = "direct",
    ) + mapOf("support_code" to "ABCD-EFGH", "hw_cpu_cores" to 8, "hw_gpu" to "Apple M2")

    private fun exitEvent(props: Map<String, Any> = mapOf("sessions" to 1)): JsonObject {
        val body = DesktopTelemetryPayload.exitBody(
            apiKey = "phc_test",
            event = "session_health",
            props = props,
            registered = registered,
            distinctId = "11111111-2222-4333-8444-555555555555",
            timestampMs = 1_700_000_000_000L,
        )
        val batch = Json.parseToJsonElement(body).jsonObject["batch"] as JsonArray
        assertEquals(1, batch.size, "one event per exit POST")
        return batch[0].jsonObject
    }

    @Test
    fun exitPayloadCarriesEverySuperPropertyTheSdkPathCarries() {
        val event = exitEvent()
        val p = event["properties"]!!.jsonObject
        for (key in listOf(
            "\$app_namespace", "\$lib", "\$device_type", "\$os", "\$app_name", "\$app_version",
            "support_code", "hw_cpu_cores", "hw_gpu", "build_flavor", "install_source",
        )) assertNotNull(p[key], "exit payload is missing $key")
        assertEquals("com.tuvora.desktop", p["\$app_namespace"]!!.jsonPrimitive.content)
        assertEquals("Desktop", p["\$device_type"]!!.jsonPrimitive.content)
        assertEquals("full", p["build_flavor"]!!.jsonPrimitive.content)
        assertEquals("direct", p["install_source"]!!.jsonPrimitive.content)
        assertEquals(DesktopTelemetryPayload.LIB, p["\$lib"]!!.jsonPrimitive.content)
        assertEquals("11111111-2222-4333-8444-555555555555", event["distinct_id"]!!.jsonPrimitive.content)
        assertEquals("1", p["sessions"]!!.jsonPrimitive.content, "the event's own properties survive")
    }

    @Test
    fun sdkRegistersTheSameStaticSetTheExitPathUses() {
        val keys = registered.keys
        assertTrue(keys.containsAll(DesktopTelemetryPayload.staticSuperProperties("v", "os", "full", "direct").keys))
        assertEquals(DesktopTelemetryPayload.LIB, registered["\$lib"])
    }

    @Test
    fun newPropertyNamesSurviveThePrivacyScrubber() {
        val p = exitEvent()["properties"]!!.jsonObject
        assertTrue("build_flavor" in p && "install_source" in p, "scrubber must not redact the new properties")
    }
}
