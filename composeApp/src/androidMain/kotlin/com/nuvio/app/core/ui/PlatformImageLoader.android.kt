package com.nuvio.app.core.ui

import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.allowRgb565
import coil3.size.Precision
import com.nuvio.app.core.memory.AndroidMemoryTierProbe
import com.nuvio.app.core.memory.AppMemory
import com.nuvio.app.core.memory.MemoryTierPolicy

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(context: PlatformContext): ImageLoader.Builder =
    components {
        if (Build.VERSION.SDK_INT >= 28) {
            add(AnimatedImageDecoder.Factory())
        } else {
            add(GifDecoder.Factory())
        }
    }
        // Poster bitmaps share the ~256MB heap with ExoPlayer's media buffer (heap/4, up to
        // 64MB): Coil's default cache — 20% of memoryClass in full ARGB — claimed another
        // ~50MB of it. Cap it from the memory tier (LOW 32 / MID 64 / HIGH 96 MiB), and let
        // RGB565 + INEXACT shrink what each cached poster costs (RGB565 only applies to
        // opaque images, so logo alpha is preserved).
        .memoryCache {
            val cap = MemoryTierPolicy.imageMemoryCacheBytes(AndroidMemoryTierProbe.tier(context))
            val cache = MemoryCache.Builder().maxSizeBytes(cap).build()
            AppMemory.registry.register("image_memory_cache", cap, priority = 0) { cache.clear() }
            cache
        }
        .allowRgb565(true)
        .precision(Precision.INEXACT)
