package com.nuvio.app.core.ui

import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.allowRgb565
import coil3.size.Precision
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.MemoryTierPolicy

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(context: PlatformContext): ImageLoader.Builder =
    components {
        if (Build.VERSION.SDK_INT >= 28) {
            add(AnimatedImageDecoder.Factory())
        } else {
            add(GifDecoder.Factory())
        }
    }
        // ⚠️ This cap is a GRAPHICS-memory budget, not a heap one — the original reasoning here
        // ("poster bitmaps share the ~256MB heap with ExoPlayer's media buffer") was wrong on any
        // device since API 26. Coil's `allowHardware` defaults to TRUE, so decoded posters are
        // Bitmap.Config.HARDWARE, which lives in gralloc/EGL memory and is counted under
        // `summary.graphics`, NOT the Java heap. Confirmed by telemetry on 2026-08-16: at process
        // death graphics was 154-192MB against a Java heap of 52-71MB nowhere near its ceiling
        // (research/graphics-memory.md).
        //
        // The cap still works, it just bounds a different pool than intended — and it is sized from
        // `memoryClass`, which is a HEAP figure. Re-basing it needs the per-pool measurement in
        // research/graphics-memory.md §6; guessing again would repeat the original mistake.
        //
        // UNVERIFIED: whether `allowRgb565(true)` below does anything once the hardware-bitmap path
        // wins the config decision. Coil's docs do not state the precedence.
        .memoryCache {
            val memory = MemoryPortAccess.current()
            val cap = MemoryTierPolicy.imageMemoryCacheBytes(memory.baseTier())
            val cache = MemoryCache.Builder().maxSizeBytes(cap).build()
            memory.registerBudget("image_memory_cache", cap, priority = 0) { cache.clear() }
            cache
        }
        .allowRgb565(true)
        .precision(Precision.INEXACT)
