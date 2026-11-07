package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.analytics.PostHogPrivacy
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.SentrySettingsRepository
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.PostHogConfig
import com.posthog.logs.PostHogBeforeSendLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Desktop start-up for Telemetry v2, and the only desktop code (with [DesktopTelemetryHost]) that names the
 * PostHog SDK. [start] runs FIRST in `main()` so a crash during startup still leaves a record. The handler uses
 * no coroutines, DI or blocking locks, never throws, and always hands the crash on to the previous handler.
 */
internal object DesktopTelemetry {
    // Public client-side key — safe to ship in the binary.
    private const val PROJECT_TOKEN = "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye"
    private const val HOST = "https://us.i.posthog.com"

    private var started = false

    @Volatile
    private var lastHandled: Throwable? = null

    private val uncaught = DesktopUncaughtDispatcher(
        onFatal = { thread, error -> TelemetryRuntime.onUncaught(thread, error) },
        onNonFatal = { type, thread, frames ->
            runCatching {
                TelemetryRuntime.service?.reportError(
                    "app_nonfatal_exception",
                    mapOf("exception_type" to type, "thread" to thread, "stack" to frames.joinToString("\n")),
                )
            }
        },
    )

    @Volatile
    private var gpuName: String? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        // Handler first: a failure while the service itself is built is then still seen (and handed on).
        installCrashHandler()
        TelemetryRuntime.start(DesktopTelemetryHost { gpuName })
        Thread({ gpuName = detectGpuName() }, "TelemetryGpuProbe").apply { isDaemon = true }.start()
    }

    /** Installs (or re-installs on top of the SDK's own handler, so ours runs outermost and writes first). */
    @Synchronized
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val first = lastHandled !== error
            lastHandled = error
            if (first) uncaught.dispatch(thread.name, error)
            try { previous?.uncaughtException(thread, error) } catch (_: Throwable) { }
        }
    }

    /** PostHog setup lives here so the SDK is named in one place. Call once consent is loaded. */
    fun configurePostHog(crashReportsEnabled: Boolean, version: String) {
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
        if (crashReportsEnabled) PostHog.optIn() else PostHog.optOut()
        PostHog.register(PostHogPrivacy.GEOIP_DISABLE_PROPERTY, true)
        PostHog.register("\$app_name", "Tuvora Desktop")
        PostHog.register("\$app_version", version)
        PostHog.register("\$app_namespace", "com.tuvora.desktop")
        PostHog.register("\$device_type", "Desktop")
        PostHog.register("\$os", System.getProperty("os.name").orEmpty().take(64))
        // Re-wrap now that the SDK has installed its own handler, then register super properties.
        installCrashHandler()
        TelemetryRuntime.service?.onSdkReady()
    }

    fun setOptIn(enabled: Boolean) {
        if (enabled) PostHog.optIn() else PostHog.optOut()
    }

    /**
     * Exit path. PostHog's JVM `flush()` only queues an async upload and `close()` does not wait for it, so the
     * one event that exists only at exit (`session_health`) is POSTed synchronously (short timeout) while
     * [shuttingDown] is set; everything else gets a bounded grace period for the async queue.
     */
    fun exitAndFlush(finalize: () -> Unit) {
        DesktopSyncSender.shuttingDown = true
        runCatching(finalize)
        runCatching { PostHog.flush() }
        runCatching { Thread.sleep(EXIT_GRACE_MS) }
        runCatching { PostHog.close() }
    }

    private const val EXIT_GRACE_MS = 750L

    private fun detectGpuName(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val command = when {
            os.contains("mac") -> listOf("/usr/sbin/system_profiler", "SPDisplaysDataType")
            os.contains("linux") -> listOf("lspci")
            os.contains("win") -> listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_VideoController | Select-Object -First 1).Name",
            )
            else -> return "unknown"
        }
        return runCatching {
            val proc = ProcessBuilder(command).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(4, TimeUnit.SECONDS)) proc.destroyForcibly()
            val line = if (os.contains("mac")) {
                out.lineSequence().firstOrNull { it.trim().startsWith("Chipset Model:") }?.substringAfter(':')
            } else if (os.contains("win")) {
                out.lineSequence().firstOrNull { it.isNotBlank() }
            } else {
                out.lineSequence().firstOrNull { it.contains("VGA") || it.contains("3D controller") }?.substringAfterLast(": ")
            }
            line?.trim()?.take(64)?.takeIf { it.isNotEmpty() } ?: "unknown"
        }.getOrDefault("unknown")
    }
}

/** Files under the app-data `telemetry/` folder (macOS, Windows and Linux paths via [DesktopStorage]). */
internal class DesktopTelemetryFileStore(private val dir: Path) : TelemetryFileStore {
    override fun read(name: String): String? = runCatching {
        val f = dir.resolve(name)
        if (Files.isRegularFile(f) && Files.size(f) <= MAX_READ_BYTES) String(Files.readAllBytes(f), Charsets.UTF_8) else null
    }.getOrNull()

    override fun writeAtomic(name: String, text: String): Boolean = runCatching {
        Files.createDirectories(dir)
        val tmp = dir.resolve("$name.tmp")
        Files.write(tmp, text.toByteArray(Charsets.UTF_8))
        move(tmp, dir.resolve(name))
        true
    }.getOrDefault(false)

    override fun rename(from: String, to: String): Boolean = runCatching {
        move(dir.resolve(from), dir.resolve(to))
        true
    }.getOrDefault(false)

    override fun delete(name: String) {
        runCatching { Files.deleteIfExists(dir.resolve(name)) }
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object { const val MAX_READ_BYTES = 256L * 1024L }
}

/** The Desktop door to PostHog and the OS facts the shared service needs. */
internal class DesktopTelemetryHost(private val gpu: () -> String?) : TelemetryHost {
    private val random = SecureRandom()

    override val appVersion: String = AppVersionConfig.DESKTOP_VERSION_NAME.ifBlank { "dev" }
    override val store: TelemetryFileStore = DesktopTelemetryFileStore(DesktopStorage.rootDir.resolve("telemetry"))

    // The JVM SDK's queue is in memory: after flush() a quick quit can still lose the event, so the service keeps
    // sending.json and re-sends once more after the delivery-confirm window.
    override val deliveryDurable: Boolean = false

    override fun nowMs(): Long = System.currentTimeMillis()
    override fun randomBelow(bound: Int): Int = random.nextInt(bound)

    override fun loadSupportCode(): String? = store.read(SUPPORT_CODE_FILE)?.trim()
    override fun saveSupportCode(code: String) { store.writeAtomic(SUPPORT_CODE_FILE, code) }

    override fun consentEnabled(): Boolean {
        SentrySettingsRepository.ensureLoaded()
        return SentrySettingsRepository.enabled.value
    }

    override fun capture(event: String, props: Map<String, Any>, timestampMs: Long?) {
        // Consent is the source of truth. The SDK's opt-in follows it asynchronously (a collector), so a
        // "Turn on" immediately followed by "Send report" would be dropped by an SDK that is still opted out.
        if (consentEnabled() && PostHog.isOptOut()) PostHog.optIn()
        if (DesktopSyncSender.shuttingDown && event == SESSION_HEALTH_EVENT &&
            DesktopSyncSender.send(event, props + registeredProps(), timestampMs)
        ) return
        if (timestampMs != null) PostHog.capture(event, properties = props, timestamp = Date(timestampMs))
        else PostHog.capture(event, properties = props)
    }

    override fun captureException(error: Throwable, props: Map<String, Any>) {
        PostHog.captureException(throwable = error, properties = props)
    }

    override fun flush() = PostHog.flush()

    override fun registerSuperProperties(props: Map<String, Any>) {
        for ((k, v) in props) PostHog.register(k, v)
    }

    override fun hardwareProfile(): Map<String, Any> = buildMap {
        put("hw_os", System.getProperty("os.name").orEmpty().take(64))
        put("hw_os_version", System.getProperty("os.version").orEmpty().take(32))
        put("hw_arch", System.getProperty("os.arch").orEmpty().take(16))
        put("hw_cpu_cores", Runtime.getRuntime().availableProcessors())
        totalRamMb()?.let { put("hw_total_ram_mb", it) }
        put("hw_gpu", gpu() ?: "unknown")
    }

    override fun memorySample(): Map<String, Long> {
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        return buildMap {
            put("java_mb", (rt.totalMemory() - rt.freeMemory()) / mb)
            put("java_max_mb", rt.maxMemory() / mb)
            osBean()?.let { put("ram_free_mb", it.freeMemorySize / mb) }
        }
    }

    private fun totalRamMb(): Long? = osBean()?.let { it.totalMemorySize / (1024L * 1024L) }

    private fun osBean(): com.sun.management.OperatingSystemMXBean? = runCatching {
        java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    }.getOrNull()

    private fun registeredProps(): Map<String, Any> = buildMap {
        loadSupportCode()?.let { put("support_code", it) }
        put("\$app_name", "Tuvora Desktop")
        put("\$app_version", appVersion)
        put("\$os", System.getProperty("os.name").orEmpty().take(64))
    }

    private companion object {
        const val SUPPORT_CODE_FILE = "support_code.txt"
        const val SESSION_HEALTH_EVENT = "session_health"
    }
}
