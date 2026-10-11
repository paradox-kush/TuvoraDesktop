package com.nuvio.app.features.player

/**
 * Hands an engine's controller the loopback twin of a subtitle URL that lives on a pinned (self-signed) media server.
 * Everything else is forwarded untouched; a URL that is not on the pinned server comes back from [rewrite] unchanged.
 */
internal class RelayedSubtitleController(
    private val delegate: PlayerEngineController,
    private val rewrite: (String) -> String,
) : PlayerEngineController by delegate {
    override fun setSubtitleUri(url: String) = delegate.setSubtitleUri(rewrite(url))
}
