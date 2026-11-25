package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.telemetry.api.QaMirror
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Desktop sink for the QA mirror. The switch is the JVM property `tuvora.qa=1` or the environment variable
 * `TUVORA_QA=1`, read once at startup ([install]). On, every signal is written as a `TuvoraQa {json}` line to stdout
 * AND appended to a log file, because a packaged GUI app has no console on Windows and none the lab can attach to on
 * macOS. The file is local only; it is never uploaded.
 *
 * Log file (the app has no other log file):
 * - macOS:   `~/Library/Application Support/Tuvora/logs/TuvoraQa.log`
 * - Windows: `%APPDATA%\Tuvora\logs\TuvoraQa.log`
 * - Linux:   `${XDG_CONFIG_HOME:-~/.config}/tuvora/logs/TuvoraQa.log`
 * It is truncated at startup once it passes [MAX_BYTES]. An installed build can be switched on without a rebuild with
 * `TUVORA_QA=1` in the environment (all three OSes) or `JAVA_TOOL_OPTIONS=-Dtuvora.qa=1`.
 */
internal object DesktopQaMirror {
    const val FILE_NAME = "TuvoraQa.log"
    private const val MAX_BYTES = 5L * 1024L * 1024L
    private val lock = Any()

    fun logFile(): Path = DesktopStorage.rootDir.resolve("logs").resolve(FILE_NAME)

    fun install() {
        val on = QaMirror.enabledBy(System.getProperty(QaMirror.PROPERTY), System.getenv(QaMirror.ENV))
        if (!on) {
            QaMirror.configure(false) {}
            return
        }
        val file = runCatching { logFile().also { prepare(it) } }.getOrNull()
        QaMirror.configure(true) { line ->
            println(line)
            if (file != null) append(file, line)
        }
    }

    private fun prepare(file: Path) {
        Files.createDirectories(file.parent)
        if (Files.isRegularFile(file) && Files.size(file) > MAX_BYTES) Files.delete(file)
    }

    private fun append(file: Path, line: String) {
        synchronized(lock) {
            runCatching {
                Files.write(
                    file,
                    (line + "\n").toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }
}
