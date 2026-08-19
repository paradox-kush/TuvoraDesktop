package com.nuvio.app

import android.app.Application
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import com.nuvio.app.core.analytics.PostHogPrivacy
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.MemoryTier
import com.nuvio.app.core.contracts.MemoryTierPolicy
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
        // Resolve the app-wide memory tier once, before anything sizes a cache from it. The OS's
        // own words (ActivityManager) feed the neutral policy; null never happens in practice and
        // falls to the bigger cache, as before. (Desktop's Android target is vestigial — the real
        // desktop entrypoint is Main.kt, which sets the tier to HIGH.)
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryTier = if (activityManager == null) MemoryTier.HIGH
            else MemoryTierPolicy.androidTier(activityManager.isLowRamDevice, activityManager.memoryClass)
        MemoryPortAccess.current().setBaseTier(memoryTier)
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Since Android 14 only these two constants fire (the rest died in 14, formally
        // deprecated in 15) — both mean the UI left the screen: drop every registered
        // cache. Truth is on disk; the windows repopulate on return.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            MemoryPortAccess.current().trimCaches()
        }
        AppExitReporter.recordMemorySnapshot(this, "trim_memory", level)
    }
}
