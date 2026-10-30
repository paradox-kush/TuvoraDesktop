package com.nuvio.app.features.player

/** Upstream VOD default and fork live default; an explicit user/session choice always wins. */
internal object PlaybackEngineDefaults {
    fun forSession(
        configured: AndroidPlaybackEngine,
        hasExplicitChoice: Boolean,
        sessionOverride: AndroidPlaybackEngine?,
        isLive: Boolean,
    ): AndroidPlaybackEngine = sessionOverride ?: if (isLive && !hasExplicitChoice) {
        AndroidPlaybackEngine.Libmpv
    } else configured
}
