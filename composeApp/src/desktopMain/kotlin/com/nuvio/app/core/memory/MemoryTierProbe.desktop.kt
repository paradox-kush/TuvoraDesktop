package com.nuvio.app.core.memory

/**
 * Desktop's probe: always HIGH — the JVM has Runtime facts and nothing else worth trusting
 * (no isLowRamDevice, no pressure events; never emulate another OS's signals here). The
 * bound lives in build.gradle.kts instead: -Xmx1g plus the Skiko GPU resource cache cap,
 * so HIGH-tier budgets are sized against a known 1 GiB heap, not JVM ergonomics' 25% of
 * the machine. Runtime.maxMemory() is surfaced for diagnostics only.
 */
internal object DesktopMemoryTierProbe {

    private var initialized = false

    fun tier(): MemoryTier {
        if (!initialized) {
            initialized = true
            AppMemory.setBaseTier(MemoryTierPolicy.desktopTier())
        }
        return MemoryTierPolicy.desktopTier()
    }

    /** The -Xmx actually in force (diagnostics; the tier never keys off it). */
    fun maxHeapBytes(): Long = Runtime.getRuntime().maxMemory()
}
