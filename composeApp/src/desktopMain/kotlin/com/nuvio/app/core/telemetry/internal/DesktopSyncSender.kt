package com.nuvio.app.core.telemetry.internal

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A synchronous, bounded POST of one event to PostHog's batch endpoint, used only at process exit where the
 * SDK's async queue has no time to drain. Same privacy scrub as the SDK path; counts and codes only.
 */
internal object DesktopSyncSender {
    @Volatile
    var shuttingDown = false

    private const val URL = "https://us.i.posthog.com/batch/"

    /** Where [send] posts. Only a test points it elsewhere (a localhost server); production never changes it. */
    @Volatile
    internal var endpoint: String = URL
    private const val API_KEY = "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye"
    private const val TIMEOUT_MS = 3_000L

    /**
     * True when PostHog answered 2xx within the deadline. Never throws. [props] must already hold the registered
     * super properties (see [DesktopTelemetryPayload.exitBody]); [distinctId] is the install id.
     */
    fun send(event: String, props: Map<String, Any>, registered: Map<String, Any>, distinctId: String, timestampMs: Long?): Boolean = try {
        val body = DesktopTelemetryPayload.exitBody(
            apiKey = API_KEY,
            event = event,
            props = props,
            registered = registered,
            distinctId = distinctId,
            timestampMs = timestampMs ?: System.currentTimeMillis(),
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build()
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofMillis(TIMEOUT_MS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
    } catch (_: Throwable) {
        false
    }
}
