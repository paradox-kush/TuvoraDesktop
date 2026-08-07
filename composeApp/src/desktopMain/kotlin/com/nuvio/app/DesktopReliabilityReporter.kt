package com.nuvio.app

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.analytics.PostHogPrivacy
import com.nuvio.app.features.settings.SentrySettingsRepository
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.PostHogConfig
import com.posthog.logs.PostHogBeforeSendLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Desktop counterpart of the Android/iOS PostHog setup.
 *
 * The JVM SDK installs exception autocapture, persists its queue, and uploads failures on the
 * next healthy run. Only coarse runtime metadata is registered; file paths, launch arguments,
 * stream URLs, provider hosts, and account values are deliberately excluded.
 */
internal object DesktopReliabilityReporter {
    private const val PROJECT_TOKEN = "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye"
    private const val HOST = "https://us.i.posthog.com"

    @Volatile
    private var started = false
    private val consentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            SentrySettingsRepository.ensureLoaded()
            val crashReportsEnabled = SentrySettingsRepository.enabled.value
            val version = AppVersionConfig.DESKTOP_VERSION_NAME.ifBlank { "dev" }
            val config = PostHogConfig(apiKey = PROJECT_TOKEN, host = HOST).apply {
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
            }
            PostHog.setup(config)
            // Lets shared code capture without depending on a platform-specific SDK.
            com.nuvio.app.core.analytics.AnalyticsSink.register { event, properties ->
                PostHog.capture(event, properties = properties)
            }
            if (crashReportsEnabled) PostHog.optIn() else PostHog.optOut()
            PostHog.register(PostHogPrivacy.GEOIP_DISABLE_PROPERTY, true)
            PostHog.register("\$app_name", "Tuvora Desktop")
            PostHog.register("\$app_version", version)
            PostHog.register("\$app_namespace", "com.tuvora.desktop")
            PostHog.register("\$device_type", "Desktop")
            PostHog.register("\$os", System.getProperty("os.name").orEmpty().take(64))

            Runtime.getRuntime().addShutdownHook(
                Thread(
                    {
                        runCatching { PostHog.flush() }
                        runCatching { PostHog.close() }
                    },
                    "PostHogShutdown",
                )
            )
            started = true
            consentScope.launch {
                SentrySettingsRepository.enabled.collect { enabled ->
                    if (enabled) PostHog.optIn() else PostHog.optOut()
                }
            }
        }
    }
}
