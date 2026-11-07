package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.analytics.PostHogPrivacy
import com.posthog.PostHog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * A synchronous, bounded POST of one event to PostHog's batch endpoint, used only at process exit where the
 * SDK's async queue has no time to drain. Same privacy scrub as the SDK path; counts and codes only.
 */
internal object DesktopSyncSender {
    @Volatile
    var shuttingDown = false

    private const val URL = "https://us.i.posthog.com/batch/"
    private const val API_KEY = "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye"
    private const val TIMEOUT_MS = 3_000L

    /** True when PostHog answered 2xx within the deadline. Never throws. */
    fun send(event: String, props: Map<String, Any>, timestampMs: Long?): Boolean = try {
        val clean = PostHogPrivacy.sanitize(props)
        val body = JsonObject(
            mapOf(
                "api_key" to JsonPrimitive(API_KEY),
                "batch" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "event" to JsonPrimitive(event),
                                "distinct_id" to JsonPrimitive(PostHog.distinctId()),
                                "timestamp" to JsonPrimitive(Instant.ofEpochMilli(timestampMs ?: System.currentTimeMillis()).toString()),
                                "properties" to JsonObject(clean.mapValues { (_, v) -> primitive(v) }),
                            ),
                        ),
                    ),
                ),
            ),
        ).toString()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build()
        val request = HttpRequest.newBuilder(URI.create(URL))
            .timeout(Duration.ofMillis(TIMEOUT_MS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
    } catch (_: Throwable) {
        false
    }

    private fun primitive(v: Any) = when (v) {
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
}
