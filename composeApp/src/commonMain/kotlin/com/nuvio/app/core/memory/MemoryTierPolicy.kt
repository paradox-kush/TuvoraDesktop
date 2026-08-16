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
    /**
     * How many catalog rows one index-write transaction may hold — LOW 100 / MID 300 / HIGH 500.
     *
     * The hub reads its movie/series rows from the SAME index database the build writes, so the
     * batch size is really "how long the UI can be blocked". Measured on a 2 GB Onn 4K TV box
     * (2026-08-16): at 5,000 rows — an UPDATE-or-INSERT plus one INSERT per normalized key, so
     * ~25,000 statements per transaction — the hub's category reads queued behind the writer long
     * enough that categories looked empty and the whole app felt broken until the build finished.
     *
     * Smaller batches cut the lock hold AND the heap peak: the old 5,000 was picked against
     * "materialize the whole catalog", never against a few hundred. Numbers are StreamVault's
     * (CatalogSyncRuntimeProfile), whose tier cuts [androidTier] already matches.
     */
    fun indexBatchSize(tier: MemoryTier): Int = when (tier) {
        MemoryTier.LOW -> 100
        MemoryTier.MID -> 300
        MemoryTier.HIGH -> 500
    }

    fun imageMemoryCacheBytes(tier: MemoryTier): Long = when (tier) {
        MemoryTier.LOW -> 32L * MIB
        MemoryTier.MID -> 64L * MIB
        MemoryTier.HIGH -> 96L * MIB
    }

    private const val MIB = 1024L * 1024
    private const val GIB = 1024L * 1024 * 1024
}
