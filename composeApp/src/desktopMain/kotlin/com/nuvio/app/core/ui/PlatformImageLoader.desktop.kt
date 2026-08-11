package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(context: PlatformContext): ImageLoader.Builder =
    // JVM Coil sizes its default cache as 15% of a hardcoded 512MiB "total memory" (~77MiB).
    // Make the bound explicit and a little roomier instead: desktop grids show far more
    // posters per screen than the phones, and the JVM heap has gigabytes of headroom.
    memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(128L * 1024 * 1024)
            .build()
    }
