package com.nuvio.app.core.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.allowRgb565
import coil3.size.Precision

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
        // ~50MB of it. Cap it like TV does, and let RGB565 + INEXACT shrink what each cached
        // poster costs (RGB565 only applies to opaque images, so logo alpha is preserved).
        .memoryCache {
            val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.isLowRamDevice == true
            MemoryCache.Builder()
                .maxSizeBytes(if (lowRam) 32L * 1024 * 1024 else 64L * 1024 * 1024)
                .build()
        }
        .allowRgb565(true)
        .precision(Precision.INEXACT)
