package com.nuvio.app.core.memory

import platform.Foundation.NSProcessInfo

/**
 * iOS's honest self-measurement: NSProcessInfo.physicalMemory picks the resting tier
 * (≤2 GiB LOW, ≤3 GiB MID — jetsam limits track the device's RAM class). The DYNAMIC side
 * — DispatchSource.memoryPressure — lives in Swift (iOSApp.swift) and feeds
 * [AppMemory.onPressure]/[AppMemory.onRelax]; os_proc_available_memory would need a cinterop
 * (absent from the Kotlin/Native 2.3.0 platform klibs, checked 2026-08-15).
 */
internal object IosMemoryTierProbe {

    private var cached: MemoryTier? = null

    fun tier(): MemoryTier = cached ?: run {
        val tier = MemoryTierPolicy.iosTier(NSProcessInfo.processInfo.physicalMemory.toLong())
        cached = tier
        AppMemory.setBaseTier(tier)
        tier
    }
}
