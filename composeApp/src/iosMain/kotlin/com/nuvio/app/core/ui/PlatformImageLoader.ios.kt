package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.MemoryTierPolicy

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(context: PlatformContext): ImageLoader.Builder =
    // Coil's iOS default cache was the one image cache we never capped — cap it from the
    // memory tier like Android/desktop (LOW 32 / MID 64 / HIGH 96 MiB) and register it so
    // the pressure listener (DispatchSource.memoryPressure via AppMemory) can drop it.
    memoryCache {
        val memory = MemoryPortAccess.current()
        val cap = MemoryTierPolicy.imageMemoryCacheBytes(memory.baseTier())
        val cache = MemoryCache.Builder().maxSizeBytes(cap).build()
        memory.registerBudget("image_memory_cache", cap, priority = 0) { cache.clear() }
        cache
    }
