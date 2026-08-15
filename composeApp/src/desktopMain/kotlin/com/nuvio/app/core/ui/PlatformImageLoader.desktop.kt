package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import com.nuvio.app.core.memory.AppMemory
import com.nuvio.app.core.memory.DesktopMemoryTierProbe
import com.nuvio.app.core.memory.MemoryTierPolicy

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(context: PlatformContext): ImageLoader.Builder =
    // JVM Coil sizes its default cache as 15% of a hardcoded 512MiB "total memory" (~77MiB).
    // Size it from the memory tier instead (desktop is always HIGH → 96MiB) and register it
    // in the budget registry, so every platform draws the image budget from one policy —
    // sized against the explicit -Xmx1g bound, not the old gigabytes-of-headroom assumption.
    memoryCache {
        val cap = MemoryTierPolicy.imageMemoryCacheBytes(DesktopMemoryTierProbe.tier())
        val cache = MemoryCache.Builder().maxSizeBytes(cap).build()
        AppMemory.registry.register("image_memory_cache", cap, priority = 0) { cache.clear() }
        cache
    }
