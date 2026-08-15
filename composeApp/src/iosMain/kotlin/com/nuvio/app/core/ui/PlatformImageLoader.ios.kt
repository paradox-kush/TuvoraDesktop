package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import com.nuvio.app.core.memory.AppMemory
import com.nuvio.app.core.memory.IosMemoryTierProbe
import com.nuvio.app.core.memory.MemoryTierPolicy

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(context: PlatformContext): ImageLoader.Builder =
    // Coil's iOS default cache was the one image cache we never capped — cap it from the
    // memory tier like Android/desktop (LOW 32 / MID 64 / HIGH 96 MiB) and register it so
    // the pressure listener (DispatchSource.memoryPressure via AppMemory) can drop it.
    memoryCache {
        val cap = MemoryTierPolicy.imageMemoryCacheBytes(IosMemoryTierProbe.tier())
        val cache = MemoryCache.Builder().maxSizeBytes(cap).build()
        AppMemory.registry.register("image_memory_cache", cap, priority = 0) { cache.clear() }
        cache
    }
