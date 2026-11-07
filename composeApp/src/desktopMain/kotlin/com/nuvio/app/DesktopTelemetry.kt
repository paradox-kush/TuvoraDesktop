package com.nuvio.app

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import com.nuvio.app.core.telemetry.api.TelemetryHost
import com.nuvio.app.core.telemetry.internal.CrashReporter
import com.nuvio.app.core.telemetry.internal.DefaultTelemetryPort
import com.nuvio.app.features.settings.SentrySettingsRepository
import com.posthog.PostHog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Desktop actuals for Telemetry v2: app-data files, memory, hardware, the crash handler. */
internal object DesktopTelemetry {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var port: DefaultTelemetryPort? = null

    @Volatile
    private var hostRef: DesktopTelemetryHost? = null

    @Volatile
    private var gpuName: String? = null

    val telemetryPort: DefaultTelemetryPort? get() = port

    /**
     * Call FIRST in main(), before DI and before any SDK: the handler must be in place for every
     * later crash and must not depend on anything else having started.
     */
    fun install() {
        if (port != null) return
        synchronized(this) {
            if (port != null) return
            val host = DesktopTelemetryHost()
            hostRef = host
            val p = DefaultTelemetryPort(host, io)
            port = p
            TelemetryAccess.install(p, host::memorySample)
            installCrashHandler(host, p)
            io.launch {
                gpuName = detectGpuName()
                runCatching { PostHog.register("hw_gpu", gpuName ?: "unknown") }
            }
            // Ring snapshot: only when something changed, at most every 30 s.
            io.launch {
                while (isActive) {
                    delay(30_000)
                    p.persistRingIfDirty()
                }
            }
        }
    }

    /** After PostHog is set up: deliver last run's crash (once, off Main) with its crash time. */
    fun sendPendingCrashAsync() {
        val host = hostRef ?: return
        io.launch { CrashReporter.sendPending(host) }
    }

    fun hardwareProfile(): Map<String, Any> = hostRef?.hardwareProfile().orEmpty()

    /** The previous run's persisted ring as a trail string, for the unexpected-exit event. */
    fun previousRunTrailJson(): String? =
        port?.previousRunTrail?.takeIf { it.isNotEmpty() }
            ?.let { com.nuvio.app.core.telemetry.internal.DiagTrailPolicy.trailJson(it, 40) }

    fun shutdown() {
        runCatching { port?.sendSessionHealth() }
        runCatching { port?.persistRingIfDirty() }
    }

    private fun installCrashHandler(host: DesktopTelemetryHost, p: DefaultTelemetryPort) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // No coroutines, DI or blocking locks here; never throw; always delegate.
            CrashReporter.writePending(
                host, p.supportCode, p.flightRecorder.lastPublished(),
                thread.name, error, java.util.UUID.randomUUID().toString(),
            )
            try {
                previous?.uncaughtException(thread, error)
            } catch (_: Throwable) {
            }
        }
    }

    private fun detectGpuName(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val command = when {
            os.contains("mac") -> listOf("/usr/sbin/system_profiler", "SPDisplaysDataType")
            os.contains("linux") -> listOf("lspci")
            else -> return "unknown"
        }
        return runCatching {
            val proc = ProcessBuilder(command).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(4, TimeUnit.SECONDS)) proc.destroyForcibly()
            val line = if (os.contains("mac")) {
                out.lineSequence().firstOrNull { it.trim().startsWith("Chipset Model:") }?.substringAfter(':')
            } else {
                out.lineSequence().firstOrNull { it.contains("VGA") || it.contains("3D controller") }?.substringAfterLast(": ")
            }
            line?.trim()?.take(64)?.takeIf { it.isNotEmpty() } ?: "unknown"
        }.getOrDefault("unknown")
    }

    private class DesktopTelemetryHost : TelemetryHost {
        private val dir: Path = DesktopStorage.rootDir.resolve("telemetry")
        private val random = SecureRandom()

        override fun nowMs(): Long = System.currentTimeMillis()

        override fun secureRandomInt(bound: Int): Int = random.nextInt(bound)

        override fun readFile(name: String): String? = runCatching {
            val f = dir.resolve(name)
            if (Files.exists(f)) String(Files.readAllBytes(f), Charsets.UTF_8) else null
        }.getOrNull()

        override fun writeFileAtomic(name: String, text: String): Boolean = runCatching {
            Files.createDirectories(dir)
            val tmp = dir.resolve("$name.tmp")
            Files.write(tmp, text.toByteArray(Charsets.UTF_8))
            Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        }.getOrDefault(false)

        override fun deleteFile(name: String) {
            runCatching { Files.deleteIfExists(dir.resolve(name)) }
        }

        override fun consentGranted(): Boolean = runCatching {
            SentrySettingsRepository.ensureLoaded()
            SentrySettingsRepository.enabled.value
        }.getOrDefault(false)

        override fun memorySample(): Map<String, Any> {
            val rt = Runtime.getRuntime()
            val mb = 1024L * 1024L
            return mapOf(
                "used_mb" to (rt.totalMemory() - rt.freeMemory()) / mb,
                "max_mb" to rt.maxMemory() / mb,
                "total_mb" to rt.totalMemory() / mb,
                "threads" to Thread.activeCount(),
            )
        }

        override fun hardwareProfile(): Map<String, Any> = buildMap {
            put("hw_os", System.getProperty("os.name").orEmpty().take(64))
            put("hw_os_version", System.getProperty("os.version").orEmpty().take(32))
            put("hw_arch", System.getProperty("os.arch").orEmpty().take(16))
            put("hw_cpu_cores", Runtime.getRuntime().availableProcessors())
            totalRamMb()?.let { put("hw_total_ram_mb", it) }
            put("hw_gpu", gpuName ?: "pending")
        }

        private fun totalRamMb(): Long? = runCatching {
            val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as com.sun.management.OperatingSystemMXBean
            bean.totalMemorySize / (1024L * 1024L)
        }.getOrNull()

        override fun appVersion(): String = AppVersionConfig.DESKTOP_VERSION_NAME.ifBlank { "dev" }

        override fun capture(event: String, props: Map<String, Any>, atMs: Long?) {
            if (atMs != null) PostHog.capture(event, properties = props, timestamp = Date(atMs))
            else PostHog.capture(event, properties = props)
        }

        override fun flush() {
            runCatching { PostHog.flush() }
        }
    }
}
