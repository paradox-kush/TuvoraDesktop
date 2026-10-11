package com.nuvio.app.features.servers

import coil3.PlatformContext
import coil3.svg.SvgDecoder
import com.nuvio.app.buildAppImageLoader
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The loader the desktop app builds must carry the shared components (custom-poster fallback, SVG, pinned-server artwork)
 * AND the platform hook's own (GIF). Coil keeps only the LAST `components {}` set on a builder, so a platform hook that
 * sets its own registry silently replaces the one `buildAppImageLoader` builds.
 */
class AppImageLoaderWiringTest {
    @Test
    fun `the desktop image loader keeps the poster fallback the svg decoder and the pinned artwork fetcher`() {
        val loader = buildAppImageLoader(PlatformContext.INSTANCE)
        assertTrue(loader.components.interceptors.any { it is CustomPosterFallbackInterceptor }, "custom poster fallback interceptor missing")
        assertTrue(loader.components.decoderFactories.any { it is SvgDecoder.Factory }, "svg decoder missing")
        assertTrue(loader.components.fetcherFactories.any { it.first is PinnedServerImageFetcherFactory }, "pinned artwork fetcher missing")
    }

    @Test
    fun `the pinned artwork fetcher comes before the normal network fetcher and the gif decoder is kept`() {
        val loader = buildAppImageLoader(PlatformContext.INSTANCE)
        val factories = loader.components.fetcherFactories.map { it.first }
        val pinned = factories.indexOfFirst { it is PinnedServerImageFetcherFactory }
        val firstNetwork = factories.indexOfFirst { it.javaClass.name.contains("NetworkFetcher") }
        assertTrue(pinned >= 0, "pinned artwork fetcher missing")
        assertTrue(firstNetwork < 0 || pinned < firstNetwork, "the pinned factory must come before the normal network fetcher")
        assertTrue(
            loader.components.decoderFactories.any { it.javaClass.name.contains("SkiaGifDecoder") },
            "the platform hook's GIF decoder must still be registered",
        )
    }
}
