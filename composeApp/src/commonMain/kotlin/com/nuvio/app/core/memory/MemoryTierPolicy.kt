package com.nuvio.app.core.memory

/**
 * Pure tier decisions — the platform probes feed OS facts in, the tier comes out.
 * No platform types here so the rules test on every runner.
 */
internal object MemoryTierPolicy {

    /**
     * Android (phone + TV): the OS's own words — isLowRamDevice / memoryClass (MB).
     * memoryClass stays the floor everywhere: budget TV boxes declare no Media
     * Performance Class, so MPC can only ever upgrade phones, never gate boxes.
     */
    fun androidTier(isLowRamDevice: Boolean, memoryClassMb: Int): MemoryTier = when {
        isLowRamDevice || memoryClassMb <= 192 -> MemoryTier.LOW
        memoryClassMb <= 320 -> MemoryTier.MID
        else -> MemoryTier.HIGH
    }

    /** iOS: ProcessInfo.physicalMemory picks the resting tier (≤2 GiB LOW, ≤3 GiB MID). */
    fun iosTier(physicalMemoryBytes: Long): MemoryTier = when {
        physicalMemoryBytes <= 2L * GIB -> MemoryTier.LOW
        physicalMemoryBytes <= 3L * GIB -> MemoryTier.MID
        else -> MemoryTier.HIGH
    }

    /** Desktop: always HIGH — but bounded (-Xmx1g + Skiko GPU cache cap in build.gradle.kts). */
    fun desktopTier(): MemoryTier = MemoryTier.HIGH

    /** Image memory cache budget per tier (Coil): LOW 32 / MID 64 / HIGH 96 MiB. */
    fun imageMemoryCacheBytes(tier: MemoryTier): Long = when (tier) {
        MemoryTier.LOW -> 32L * MIB
        MemoryTier.MID -> 64L * MIB
        MemoryTier.HIGH -> 96L * MIB
    }

    private const val MIB = 1024L * 1024
    private const val GIB = 1024L * 1024 * 1024
}
