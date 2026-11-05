package com.nuvio.app.features.player.desktop

import java.lang.management.ManagementFactory

/**
 * Installed physical memory, as the JVM reports it (the honest desktop fact: mpv's demuxer cache is native memory,
 * not heap, so `-Xmx` says nothing about it). 0 = the JVM would not say; the live pause budget then never raises
 * the caches.
 */
internal object DesktopPhysicalMemory {
    fun bytes(): Long = runCatching {
        (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize
    }.getOrDefault(0L).coerceAtLeast(0L)
}
