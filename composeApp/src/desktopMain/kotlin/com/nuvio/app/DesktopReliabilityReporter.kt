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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import kotlin.io.path.exists

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

            val processExitTracker = DesktopProcessExitTracker.start(version)

            Runtime.getRuntime().addShutdownHook(
                Thread(
                    {
                        processExitTracker.markCleanExit()
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

/**
 * Detects exits that bypass both JVM exception autocapture and the shutdown hook (native abort,
 * SIGKILL, OS resource termination, power loss). A per-process marker avoids treating a second
 * simultaneously-running Tuvora instance as a crash. Stale markers are reported on next launch.
 */
private class DesktopProcessExitTracker private constructor(
    private val marker: Path,
) {
    fun markCleanExit() {
        runCatching { Files.deleteIfExists(marker) }
    }

    companion object {
        private const val MARKER_PREFIX = "run-"
        private const val MARKER_SUFFIX = ".properties"

        fun start(version: String): DesktopProcessExitTracker {
            val directory = com.nuvio.app.core.storage.DesktopStorage.rootDir.resolve("reliability")
            runCatching { Files.createDirectories(directory) }
            reportStaleMarkers(directory)

            val pid = ProcessHandle.current().pid()
            val marker = directory.resolve("$MARKER_PREFIX$pid$MARKER_SUFFIX")
            val properties = Properties().apply {
                setProperty("pid", pid.toString())
                setProperty("started_at_ms", System.currentTimeMillis().toString())
                setProperty("app_version", version.take(64))
            }
            runCatching {
                Files.newOutputStream(
                    marker,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { properties.store(it, "Tuvora desktop run marker") }
            }
            return DesktopProcessExitTracker(marker)
        }

        private fun reportStaleMarkers(directory: Path) {
            if (!directory.exists()) return
            runCatching {
                Files.list(directory).use { paths ->
                    paths.filter { it.fileName.toString().startsWith(MARKER_PREFIX) && it.fileName.toString().endsWith(MARKER_SUFFIX) }
                        .forEach { path -> inspectMarker(path) }
                }
            }
        }

        private fun inspectMarker(path: Path) {
            val properties = Properties()
            runCatching { Files.newInputStream(path).use(properties::load) }
            val pid = properties.getProperty("pid")?.toLongOrNull()
            if (pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return

            val failedVersion = properties.getProperty("app_version")?.take(64) ?: "unknown"
            val startedAt = properties.getProperty("started_at_ms")?.toLongOrNull()
            val props = buildMap<String, Any> {
                put("reason", "unexpected_process_exit")
                put("diagnostic_source", "desktop_run_marker")
                put("failed_app_version", failedVersion)
                startedAt?.let { put("failed_run_started_at_ms", it) }
            }
            PostHog.capture("app_exit", properties = props)
            PostHog.captureException(
                throwable = DesktopUnexpectedExitException(),
                properties = props + mapOf(
                    "\$exception_fingerprint" to "desktop_process_exit:unexpected",
                    "\$exception_level" to "fatal",
                    "synthetic_process_exit" to true,
                ),
            )
            runCatching { Files.deleteIfExists(path) }
        }
    }
}

private class DesktopUnexpectedExitException : RuntimeException("Previous desktop process exited unexpectedly")
