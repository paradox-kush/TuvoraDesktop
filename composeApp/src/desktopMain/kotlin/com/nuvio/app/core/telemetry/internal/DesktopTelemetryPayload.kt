package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.analytics.PostHogPrivacy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Pure builders for what Desktop tells PostHog, so the SDK path and the exit-time POST carry the same super
 * properties and a test can prove it without a network.
 */
internal object DesktopTelemetryPayload {
    /**
     * `$lib` for every Desktop event. The PostHog JVM SDK sets no `$lib` of its own (only its Android context does),
     * so without this Desktop arrives as "unknown". The value is the SDK's own `sdkName` default.
     */
    const val LIB = "posthog-java"

    /** The properties known before the hardware probe and support code: registered with the SDK and sent at exit. */
    fun staticSuperProperties(
        version: String,
        osName: String,
        buildFlavor: String,
        installSource: String,
    ): Map<String, Any> = mapOf(
        "\$app_name" to "Tuvora Desktop",
        "\$app_version" to version,
        "\$app_namespace" to "com.tuvora.desktop",
        "\$device_type" to "Desktop",
        "\$os" to osName.take(64),
        "\$lib" to LIB,
        "build_flavor" to buildFlavor,
        "install_source" to installSource,
    )

    /** One `/batch/` request body: the event's own [props] plus every [registered] super property, scrubbed. */
    fun exitBody(
        apiKey: String,
        event: String,
        props: Map<String, Any>,
        registered: Map<String, Any>,
        distinctId: String,
        timestampMs: Long,
    ): String {
        val clean = PostHogPrivacy.sanitize(registered + props)
        return JsonObject(
            mapOf(
                "api_key" to JsonPrimitive(apiKey),
                "batch" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "event" to JsonPrimitive(event),
                                "distinct_id" to JsonPrimitive(distinctId),
                                "timestamp" to JsonPrimitive(Instant.ofEpochMilli(timestampMs).toString()),
                                "properties" to JsonObject(clean.mapValues { (_, v) -> primitive(v) }),
                            ),
                        ),
                    ),
                ),
            ),
        ).toString()
    }

    private fun primitive(v: Any) = when (v) {
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
}
