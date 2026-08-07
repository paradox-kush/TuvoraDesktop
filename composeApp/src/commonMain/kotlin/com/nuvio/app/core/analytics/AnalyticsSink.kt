package com.nuvio.app.core.analytics

/**
 * Where shared code sends analytics events.
 *
 * PostHog is configured per platform and its SDKs are not multiplatform — Android sets up
 * `PostHogAndroid` in `NuvioApplication`, iOS sets up `PostHogSDK` in `iOSApp.init()`. Rather
 * than an expect/actual per platform, each host registers a sink here once at startup and
 * shared code captures through it.
 *
 * Consent is not re-checked here: both SDKs are configured opt-out until the user enables
 * crash reports, so a capture with consent withheld is dropped by the SDK itself.
 *
 * Unregistered (a host that has not called [register], or a test) silently drops events.
 */
object AnalyticsSink {

    private var sink: ((String, Map<String, Any>) -> Unit)? = null

    /** Called once by each platform host at startup. Safe to call again to replace. */
    fun register(sink: (String, Map<String, Any>) -> Unit) {
        this.sink = sink
    }

    fun capture(event: String, properties: Map<String, Any>) {
        sink?.invoke(event, properties)
    }
}
