package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.analytics.PostHogPrivacy
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryBuckets
import com.nuvio.app.features.settings.SentrySettingsRepository
import com.posthog.PostHog
import com.posthog.internal.PostHogMemoryPreferences
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

    /** Queue staleness measured at launch (diagnostics ON only); rides on `session_health` as two numbers. */
    @Volatile
    var queueBacklog: TelemetryQueueBacklog? = null
        private set

    /** The SDK's on-disk event queue lives beside the install id (SDK adds `<apiKey>/` under it). */
    private val queueRoot: Path = DesktopStorage.rootDir.resolve("telemetry").resolve("posthog-queue")

    /** Where the install id lives: beside the support code. Shared by both PostHog paths. */
    private val identity = DesktopInstallIdentity(DesktopTelemetryFileStore(DesktopStorage.rootDir.resolve("telemetry")))

    @Volatile
    private var installId: String? = null

    /** The SDK's preference store. Its "distinctId"/"anonymousId" keys are what `PostHog.distinctId()` reads. */
    private var sdkPrefs: PostHogMemoryPreferences? = null

    /** Every super property registered so far, so the exit-time POST can carry the same set. */
    private val registered = ConcurrentHashMap<String, Any>()

    fun registeredSnapshot(): Map<String, Any> = HashMap(registered)

    /** The install id for the exit path; the SDK's own id only when none is stored (diagnostics never on). */
    fun distinctIdForExit(): String = installId ?: identity.peek() ?: PostHog.distinctId()

    fun register(key: String, value: Any) {
        registered[key] = value
        PostHog.register(key, value)
    }

    /**
     * Makes the stored install id the SDK's distinct id. Only [crashReportsEnabled] installs create the file;
     * an opted-out install reuses an id it already has and writes nothing.
     */
    private fun applyInstallId(create: Boolean) {
        val id = (if (create) identity.loadOrCreate() else identity.peek()) ?: return
        installId = id
        sdkPrefs?.apply {
            setValue("distinctId", id)
            setValue("anonymousId", id)
        }
    }

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
        // QA mirror: read once, here, before anything can play. Off (and free) unless -Dtuvora.qa=1 / TUVORA_QA=1.
        DesktopQaMirror.install()
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
        // The JVM SDK's default preference store is in memory, so its generated id dies with the process. Own the
        // store so the stored install id can be seeded into it before the first event.
        val prefs = PostHogMemoryPreferences().also { sdkPrefs = it }
        val config = DesktopPostHogConfig.build(PROJECT_TOKEN, HOST, prefs, crashReportsEnabled, version, queueRoot)
        applyInstallId(create = crashReportsEnabled)
        // Opted out: nothing queued earlier may be uploaded later. Opted in: note how stale the leftover queue is.
        if (crashReportsEnabled) {
            queueBacklog = DesktopTelemetryQueue.scan(queueRoot, PROJECT_TOKEN, System.currentTimeMillis())
        } else {
            DesktopTelemetryQueue.purge(queueRoot, PROJECT_TOKEN)
        }
        PostHog.setup(config)
        if (crashReportsEnabled) PostHog.optIn() else PostHog.optOut()
        PostHog.register(PostHogPrivacy.GEOIP_DISABLE_PROPERTY, true)
        for ((k, v) in DesktopTelemetryPayload.staticSuperProperties(
            version = version,
            osName = System.getProperty("os.name").orEmpty(),
            buildFlavor = AppFeaturePolicy.buildFlavor,
            installSource = INSTALL_SOURCE,
        )) register(k, v)
        // Re-wrap now that the SDK has installed its own handler, then register super properties.
        installCrashHandler()
        TelemetryRuntime.service?.onSdkReady()
    }

    fun setOptIn(enabled: Boolean) {
        // Turning diagnostics on later is the first moment an opted-out install may get an id.
        if (enabled && installId == null) applyInstallId(create = true)
        if (enabled) PostHog.optIn() else {
            PostHog.optOut()
            DesktopTelemetryQueue.purge(queueRoot, PROJECT_TOKEN)
        }
    }

    /**
     * Exit path. PostHog's JVM `flush()` only queues an async upload and `close()` does not wait for it, so the
     * one event that exists only at exit (`session_health`) is POSTed synchronously (short timeout) while
     * [shuttingDown] is set; everything else gets a bounded grace period for the async queue.
     */
    fun exitAndFlush(sendHealthNow: () -> Unit = {}, finalize: () -> Unit) {
        DesktopExitHook.run(
            sendHealthNow = sendHealthNow,
            finalize = finalize,
            flush = { PostHog.flush() },
            close = { PostHog.close() },
        )
    }

    /** How Desktop is installed (same value on every Desktop build; other platforms report their own). */
    private const val INSTALL_SOURCE = "direct"

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

    // The SDK queue is now on disk (see DesktopPostHogConfig.storagePrefix), but a write is asynchronous: a quick
    // quit right after flush() can still lose the event. Left false so the service keeps sending.json and
    // re-sends once more after the delivery-confirm window (the owner may flip this later).
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
        if (!consentEnabled()) return
        if (PostHog.isOptOut()) PostHog.optIn()
        // Delivery-failure signal: how stale the on-disk queue was at launch (numbers only). Diagnostics are ON here.
        val payload = if (event == SESSION_HEALTH_EVENT) DesktopTelemetry.queueBacklog?.let { props + it.asProps() } ?: props else props
        // first_leave is captured on this same exit path (quit / last window closed), so it rides the same bounded POST.
        if (DesktopSyncSender.shuttingDown && DesktopSyncSender.sendsSynchronouslyAtExit(event, props) &&
            DesktopSyncSender.send(
                event, payload, DesktopTelemetry.registeredSnapshot(), DesktopTelemetry.distinctIdForExit(), timestampMs,
            )
        ) return
        if (timestampMs != null) PostHog.capture(event, properties = payload, timestamp = Date(timestampMs))
        else PostHog.capture(event, properties = payload)
    }

    override fun captureException(error: Throwable, props: Map<String, Any>) {
        PostHog.captureException(throwable = error, properties = props)
    }

    override fun flush() = PostHog.flush()

    override fun registerSuperProperties(props: Map<String, Any>) {
        for ((k, v) in props) DesktopTelemetry.register(k, v)
    }

    override fun hardwareProfile(): Map<String, Any> = buildMap {
        put("hw_os", System.getProperty("os.name").orEmpty().take(64))
        put("hw_os_version", System.getProperty("os.version").orEmpty().take(32))
        put("hw_arch", System.getProperty("os.arch").orEmpty().take(16))
        put("hw_cpu_cores", Runtime.getRuntime().availableProcessors())
        totalRamMb()?.let { put("hw_total_ram_mb", it) }
        PlaybackTelemetryBuckets.ramClass(totalRamMb())?.let { put("ram_class", it) }
        put("hw_gpu", gpu() ?: "unknown")
    }

    /**
     * JVM and process facts only. The JVM has no PSS and no native-heap number worth trusting, so those fields are
     * absent here (never estimated): `java_mb` heap used, `java_max_mb` heap ceiling, `ram_free_mb` OS free memory,
     * `threads` live JVM threads, `fds` open file descriptors (macOS and Linux only; Windows has no such count).
     */
    override fun memorySample(): Map<String, Long> {
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        return buildMap {
            put("java_mb", (rt.totalMemory() - rt.freeMemory()) / mb)
            put("java_max_mb", rt.maxMemory() / mb)
            osBean()?.let { put("ram_free_mb", it.freeMemorySize / mb) }
            runCatching { java.lang.management.ManagementFactory.getThreadMXBean().threadCount.toLong() }.getOrNull()?.let { put("threads", it) }
            runCatching {
                (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.UnixOperatingSystemMXBean)
                    ?.openFileDescriptorCount
            }.getOrNull()?.takeIf { it >= 0L }?.let { put("fds", it) }
        }
    }

    private fun totalRamMb(): Long? = osBean()?.let { it.totalMemorySize / (1024L * 1024L) }

    private fun osBean(): com.sun.management.OperatingSystemMXBean? = runCatching {
        java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    }.getOrNull()

    private companion object {
        const val SUPPORT_CODE_FILE = "support_code.txt"
        const val SESSION_HEALTH_EVENT = "session_health"
    }
}
