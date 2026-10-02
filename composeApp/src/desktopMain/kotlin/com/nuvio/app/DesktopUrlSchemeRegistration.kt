package com.nuvio.app

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.io.File

/**
 * Registers the `tuvora://` URL scheme for the packaged app so a provider's `tuvora://s/<code>` link opens
 * Tuvora on the setup-code page.
 *
 * - macOS: declared in the app's Info.plist by the packaging config (`CFBundleURLTypes`), nothing to do at runtime.
 * - Windows: jpackage's MSI cannot declare a URL protocol, so the app registers it per user (HKCU, no admin
 *   rights) the first time it runs from an installed copy.
 * - Linux: a per-user `.desktop` entry advertising `x-scheme-handler/tuvora`, made the default with xdg-mime.
 *
 * Best effort and idempotent: it never throws, never blocks startup (runs on a background thread), skips a
 * development run (`java`/`gradle`), and rewrites nothing that is already right. The pure builders below are
 * unit-tested; the OS calls themselves are verified by hand on each OS.
 */
internal object DesktopUrlSchemeRegistration {
    const val SCHEME = "tuvora"

    /** `reg add` arguments for the per-user protocol keys, in order. [exe] is the installed launcher. */
    fun windowsRegCommands(exe: String): List<List<String>> {
        val root = "HKCU\\Software\\Classes\\$SCHEME"
        return listOf(
            listOf("reg", "add", root, "/ve", "/d", "URL:Tuvora Protocol", "/f"),
            listOf("reg", "add", root, "/v", "URL Protocol", "/d", "", "/f"),
            listOf("reg", "add", "$root\\shell\\open\\command", "/ve", "/d", "\"$exe\" \"%1\"", "/f"),
        )
    }

    /** The per-user desktop entry that makes the scheme resolvable on Linux. */
    fun linuxDesktopEntry(exe: String): String = """
        [Desktop Entry]
        Type=Application
        Name=Tuvora
        Exec="$exe" %u
        Terminal=false
        NoDisplay=true
        MimeType=x-scheme-handler/$SCHEME;
    """.trimIndent() + "\n"

    const val LINUX_ENTRY_NAME = "tuvora-url-handler.desktop"

    /** True when [command] is the installed app launcher rather than a bare JVM (a development run). */
    fun isInstalledLauncher(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        val name = command.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name !in setOf("java", "java.exe", "javaw.exe", "gradle", "gradlew")
    }

    fun registerBestEffort() {
        Thread({
            runCatching {
                val command = ProcessHandle.current().info().command().orElse(null)
                if (!isInstalledLauncher(command)) return@runCatching
                when (DesktopHostOs.current) {
                    DesktopHostOs.WINDOWS -> windowsRegCommands(command!!).forEach { run(it) }
                    DesktopHostOs.LINUX -> registerLinux(command!!)
                    else -> Unit
                }
            }
        }, "tuvora-url-scheme").apply { isDaemon = true }.start()
    }

    private fun registerLinux(exe: String) {
        val dir = File(System.getProperty("user.home"), ".local/share/applications")
        val entry = File(dir, LINUX_ENTRY_NAME)
        val wanted = linuxDesktopEntry(exe)
        if (!entry.exists() || entry.readText() != wanted) {
            dir.mkdirs()
            entry.writeText(wanted)
            run(listOf("xdg-mime", "default", LINUX_ENTRY_NAME, "x-scheme-handler/$SCHEME"))
        }
    }

    private fun run(args: List<String>) {
        ProcessBuilder(args).redirectErrorStream(true).start().also { p ->
            p.inputStream.readBytes()
            p.waitFor()
        }
    }
}
