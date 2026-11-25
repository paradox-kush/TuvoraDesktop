package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.telemetry.api.QaMirror
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Desktop sink for the shared QA mirror (`QaMirror`, `TelemetryHost.qaMirrorLine`). The switch is the JVM property
 * `tuvora.qa=1` or the environment variable `TUVORA_QA=1`, read once when the telemetry host is created. On, every line
 * is written as `TuvoraQa {json}` to stdout AND appended to a log file, because a packaged GUI app has no console on
 * Windows and none the lab can attach to on macOS. The file is local only; it is never uploaded.
 *
 * Log file (the app has no other log file):
 * - macOS:   `~/Library/Application Support/Tuvora/logs/TuvoraQa.log`
 * - Windows: `%APPDATA%\Tuvora\logs\TuvoraQa.log`
 * - Linux:   `${XDG_CONFIG_HOME:-~/.config}/tuvora/logs/TuvoraQa.log`
 * It is truncated once, at the first write, when it has passed [MAX_BYTES]. An installed build can be switched on without
 * a rebuild with `TUVORA_QA=1` in the environment (all three OSes) or `JAVA_TOOL_OPTIONS=-Dtuvora.qa=1`.
 */
internal object DesktopQaMirror {
    const val FILE_NAME = "TuvoraQa.log"
    const val ENV = "TUVORA_QA"
    private const val MAX_BYTES = 5L * 1024L * 1024L
    private val lock = Any()
    private val file: Path? by lazy { runCatching { logFile().also { prepare(it) } }.getOrNull() }

    fun logFile(): Path = DesktopStorage.rootDir.resolve("logs").resolve(FILE_NAME)

    /** True when either source says exactly `1`. */
    fun isEnabled(property: String?, env: String?): Boolean = QaMirror.isEnabledValue(property) || QaMirror.isEnabledValue(env)

    fun enabledAtStartup(): Boolean = isEnabled(System.getProperty(QaMirror.JVM_PROPERTY), System.getenv(ENV))

    /** [line] is the shared mirror's JSON; the tag is added here so the lab greps the same `TuvoraQa` token as on Android. */
    fun write(line: String) {
        val tagged = "${QaMirror.TAG} $line"
        println(tagged)
        append(tagged)
    }

    private fun prepare(file: Path) {
        Files.createDirectories(file.parent)
        if (Files.isRegularFile(file) && Files.size(file) > MAX_BYTES) Files.delete(file)
    }

    private fun append(line: String) {
        val target = file ?: return
        synchronized(lock) {
            runCatching {
                Files.write(
                    target,
                    (line + "\n").toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }
}
