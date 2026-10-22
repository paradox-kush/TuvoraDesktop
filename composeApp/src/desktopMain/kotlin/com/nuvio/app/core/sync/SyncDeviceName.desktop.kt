package com.nuvio.app.core.sync

import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Uses the macOS ComputerName or the Windows/Linux host name supplied by the OS.
 *
 * COMPUTERNAME/HOSTNAME are read first because the reverse lookup can be slow (or return a
 * fully-qualified name from a DNS suffix) on machines with unusual network setups; the OS falls
 * back to the platform name so the list never shows a blank row.
 */
internal actual fun syncDeviceName(): String {
    // macOS's ComputerName is the name shown in System Settings; the hostname may differ.
    if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
        val computerName = runCatching {
            val process = ProcessBuilder("/usr/sbin/scutil", "--get", "ComputerName")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            try {
                if (process.waitFor(250, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
                    process.inputStream.bufferedReader().use { it.readText() }.trim()
                } else null
            } finally {
                process.destroyForcibly()
            }
        }.getOrNull()
        if (!computerName.isNullOrBlank()) return computerName
    }
    val fromEnv = sequenceOf("COMPUTERNAME", "HOSTNAME")
        .mapNotNull { System.getenv(it)?.trim() }
        .firstOrNull { it.isNotBlank() }
    if (!fromEnv.isNullOrBlank()) return fromEnv.substringBefore('.')

    val fromHost = runCatching { InetAddress.getLocalHost().hostName.trim() }.getOrNull()
    if (!fromHost.isNullOrBlank()) return fromHost.substringBefore('.')

    return System.getProperty("os.name")?.trim()?.ifBlank { null } ?: "Desktop"
}
