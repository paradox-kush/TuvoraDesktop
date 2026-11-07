package com.nuvio.app

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import com.nuvio.app.core.telemetry.internal.DesktopTelemetry
import com.nuvio.app.features.settings.SentrySettingsRepository
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
            // PostHog setup, super properties (support code, hardware) and the crash-handler re-wrap
            // live in core/telemetry/internal; routine events go through TelemetryPort.emit.
            DesktopTelemetry.configurePostHog(crashReportsEnabled, version)

            val processExitTracker = DesktopProcessExitTracker.start(version)

            Runtime.getRuntime().addShutdownHook(
                Thread(
                    {
                        processExitTracker.markCleanExit()
                        runCatching { TelemetryAccess.current().onBackground() }
                        DesktopTelemetry.flushAndClose()
                    },
                    "PostHogShutdown",
                )
            )
            started = true
            consentScope.launch {
                SentrySettingsRepository.enabled.collect { enabled -> DesktopTelemetry.setOptIn(enabled) }
            }
            // Last run's Java/Kotlin crash record goes out once, off Main, with a deadline.
            consentScope.launch { TelemetryAccess.current().sendPendingCrash() }
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
                // An unexpected desktop exit is user-visible: the shared send policy would otherwise count it.
                put("importance", "foreground")
                put("diagnostic_source", "desktop_run_marker")
                put("failed_app_version", failedVersion)
                startedAt?.let { put("failed_run_started_at_ms", it) }
            }
            val trail = TelemetryAccess.current().previousSessionTrail()
            val exitProps = if (trail != null) props + ("diag_trail" to trail) else props
            TelemetryAccess.current().emit("app_exit", exitProps)
            TelemetryAccess.current().reportException(
                DesktopUnexpectedExitException(),
                props + mapOf(
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
