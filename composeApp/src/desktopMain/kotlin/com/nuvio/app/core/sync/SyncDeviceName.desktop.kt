package com.nuvio.app.core.sync

import java.net.InetAddress

/**
 * "kush-mac", "DESKTOP-4F2A1B" — the host name is the closest thing a desktop has to the
 * user-assigned device name iOS reports, and it is what the user will recognise in the device list.
 *
 * COMPUTERNAME/HOSTNAME are read first because the reverse lookup can be slow (or return a
 * fully-qualified name from a DNS suffix) on machines with unusual network setups; the OS falls
 * back to the platform name so the list never shows a blank row.
 */
internal actual fun syncDeviceName(): String {
    val fromEnv = sequenceOf("COMPUTERNAME", "HOSTNAME")
        .mapNotNull { System.getenv(it)?.trim() }
        .firstOrNull { it.isNotBlank() }
    if (!fromEnv.isNullOrBlank()) return fromEnv.substringBefore('.')

    val fromHost = runCatching { InetAddress.getLocalHost().hostName.trim() }.getOrNull()
    if (!fromHost.isNullOrBlank()) return fromHost.substringBefore('.')

    return System.getProperty("os.name")?.trim()?.ifBlank { null } ?: "Desktop"
}
