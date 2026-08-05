package com.nuvio.app

import android.app.Application
import com.nuvio.app.core.analytics.PostHogPrivacy
import com.nuvio.app.features.settings.SentrySettingsRepository
import com.nuvio.app.features.settings.SentrySettingsStorage
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.logs.PostHogBeforeSendLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NuvioApplication : Application() {

    private val analyticsConsentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // Public client-side key — safe to ship in the binary.
        const val POSTHOG_PROJECT_TOKEN = "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye"
        const val POSTHOG_HOST = "https://us.i.posthog.com"
    }

    override fun onCreate() {
        super.onCreate()
        SentrySettingsStorage.initialize(this)
        SentrySettingsRepository.ensureLoaded()
        val crashReportsEnabled = SentrySettingsRepository.enabled.value

        val config = PostHogAndroidConfig(
            apiKey = POSTHOG_PROJECT_TOKEN,
            host = POSTHOG_HOST
        ).apply {
            // Capture uncaught exceptions as $exception events (where the app breaks).
            errorTrackingConfig.autoCapture = true
            optOut = !crashReportsEnabled
            captureApplicationLifecycleEvents = false
            captureScreenViews = false
            sendFeatureFlagEvent = false
            preloadFeatureFlags = false
            surveys = false
            captureDeepLinks = false
            sessionReplay = false
            sessionReplayConfig.screenshot = false
            sessionReplayConfig.captureLogcat = false
            tracingHeaders = emptyList()
            logs.addBeforeSend(PostHogBeforeSendLog { null })
            addBeforeSend(PostHogBeforeSend { event ->
                if (PostHogPrivacy.shouldDropEvent(event.event)) null
                else event.copy(
                    properties = PostHogPrivacy.sanitize(event.properties.orEmpty()).toMutableMap(),
                )
            })
            // Upload queued events quickly after launch: a crash queued by the previous
            // run must ship before the user navigates back into whatever crashed
            // (the default 30s starved uploads during crash-loops).
            flushIntervalSeconds = 10
        }
        PostHogAndroid.setup(this, config)
        PostHog.register(PostHogPrivacy.GEOIP_DISABLE_PROPERTY, true)
        if (crashReportsEnabled) {
            PostHog.optIn()
            AppExitReporter.reportPendingExits(this)
        } else {
            PostHog.optOut()
        }
        analyticsConsentScope.launch {
            SentrySettingsRepository.enabled.collect { enabled ->
                if (enabled) PostHog.optIn() else PostHog.optOut()
            }
        }
    }
}
