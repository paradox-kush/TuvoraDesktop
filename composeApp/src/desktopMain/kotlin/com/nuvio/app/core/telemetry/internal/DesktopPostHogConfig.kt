package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.analytics.PostHogPrivacy
import com.posthog.PostHogBeforeSend
import com.posthog.PostHogConfig
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.logs.PostHogBeforeSendLog
import java.nio.file.Path

/**
 * The one place the Desktop PostHog configuration is built, so production and the delivery tests run the SAME
 * settings against a real SDK.
 */
internal object DesktopPostHogConfig {
    fun build(
        apiKey: String,
        host: String,
        prefs: PostHogMemoryPreferences,
        crashReportsEnabled: Boolean,
        version: String,
        queueRoot: Path,
    ): PostHogConfig = PostHogConfig(apiKey = apiKey, host = host).apply {
        cachePreferences = prefs
        errorTrackingConfig.autoCapture = true
        optOut = !crashReportsEnabled
        sendFeatureFlagEvent = false
        preloadFeatureFlags = false
        surveys = false
        sessionReplay = false
        tracingHeaders = emptyList()
        logs.addBeforeSend(PostHogBeforeSendLog { null })
        addBeforeSend(PostHogBeforeSend { event ->
            if (PostHogPrivacy.shouldDropEvent(event.event)) null
            else event.copy(
                properties = PostHogPrivacy.sanitize(event.properties.orEmpty()).toMutableMap(),
            )
        })
        flushIntervalSeconds = 10
        releaseIdentifier = version
        // PostHog 6.x only ever queues events as FILES, and only when this is set; null means every captured
        // event is silently dropped. The SDK adds `<apiKey>/` under it and creates the directory lazily on the
        // first persisted event, so an opted-out install (which never enqueues) creates nothing.
        storagePrefix = queueRoot.toString()
    }
}
