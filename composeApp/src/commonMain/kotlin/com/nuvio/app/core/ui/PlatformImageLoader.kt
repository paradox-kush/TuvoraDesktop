package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext

internal expect fun ImageLoader.Builder.configurePlatformImageLoader(context: PlatformContext): ImageLoader.Builder

/**
 * Returns `true` when the platform already provides a singleton [ImageLoader]
 * (e.g. via `Application` implementing `SingletonImageLoader.Factory` on Android).
 * When `true`, the composable [setSingletonImageLoaderFactory] must be skipped
 * so it doesn't overwrite the platform-provided loader.
 */
internal expect val platformProvidesImageLoader: Boolean
