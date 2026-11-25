package com.nuvio.app

import com.nuvio.app.core.analytics.Breadcrumbs
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryKeys
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import com.nuvio.app.core.telemetry.internal.DesktopTelemetry
import com.nuvio.app.core.telemetry.internal.TelemetryRuntime
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
 * The JVM SDK installs exception autocapture and queues events as files under telemetry/posthog-queue
 * (DesktopPostHogConfig sets storagePrefix; without it the SDK drops every event), replaying leftovers
 * on the next launch. Only coarse runtime metadata is registered; file paths, launch arguments,
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
            // What the run was doing when it died: the NEXT launch reads it back as `prev_playing` / `prev_kind` /
            // `prev_engine` on `app_exit`. Desktop has no multiview, so there is no `prev_tiles`.
            Breadcrumbs.crashWriter = object : Breadcrumbs.CrashWriter {
                override fun onScreen(name: String) = Unit
                override fun onPlaybackStarted(kind: String, engine: String, surface: String) =
                    processExitTracker.notePlayback(kind, engine)
                override fun onPlaybackStopped() = processExitTracker.noteStopped()
            }

            Runtime.getRuntime().addShutdownHook(
                Thread(
                    {
                        processExitTracker.markCleanExit()
                        // session_health is sent synchronously on THIS thread, before the grace period and close
                        // (onBackground would only start it in a coroutine that the grace period can outrun).
                        DesktopTelemetry.exitAndFlush(
                            sendHealthNow = {
                                TelemetryRuntime.service?.apply { sampleMemoryOnce(); sendHealth() }
                            },
                            finalize = { TelemetryAccess.current().onBackground() },
                        )
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
    private val properties: Properties,
) {
    // One daemon worker keeps marker rewrites in order and off the UI thread.
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "RunMarkerWriter").apply { isDaemon = true }
    }

    fun markCleanExit() {
        // Let an in-flight rewrite finish first, or it could recreate the marker we are about to delete.
        runCatching { writer.shutdown(); writer.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        runCatching { Files.deleteIfExists(marker) }
    }

    fun notePlayback(kind: String, engine: String) = update {
        setProperty("playing", "1")
        setProperty("kind", kind.take(24))
        setProperty("engine", engine.take(24))
    }

    fun noteStopped() = update { setProperty("playing", "0") }

    private fun update(change: Properties.() -> Unit) {
        runCatching {
            writer.execute {
                runCatching {
                    properties.change()
                    Files.newOutputStream(
                        marker,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    ).use { properties.store(it, "Tuvora desktop run marker") }
                }
            }
        }
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
            return DesktopProcessExitTracker(marker, properties)
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
                // The run's last breadcrumb, as closed values (a marker from before this change says nothing).
                putAll(
                    PlaybackTelemetryKeys.previousRun(
                        playing = properties.getProperty("playing")?.let { it == "1" },
                        kindLabel = properties.getProperty("kind"),
                        engineLabel = properties.getProperty("engine"),
                    ),
                )
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
