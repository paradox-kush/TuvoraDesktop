package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "A full-bleed video surface owns the screen right now."
 *
 * Set by the surfaces that fill the window with video — the player route and the docked Live TV
 * screen — and read by app-level chrome that must not steal pixels from them (today: the in-app
 * update banner, which is a layout sibling of the whole app and so *shrinks* whatever is below it).
 *
 * Sibling of [com.nuvio.app.features.iptv.IptvPlaybackGate], which answers a similar question for
 * the background M3U refresh worker. That one is a plain atomic because a worker thread polls it;
 * this one is a [StateFlow] because Compose has to recompose when it flips.
 *
 * Reference counted: Live TV can hand off to the player route while both are briefly composed, and
 * a plain boolean would let the leaving screen clear a flag the arriving screen still needs.
 */
object ImmersivePlaybackGate {
    private var activeCount = 0
    private val _isActive = MutableStateFlow(false)

    /** True while at least one immersive video surface is composed. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /**
     * Marks an immersive surface as entered/left. Balance every `true` with a `false`, which the
     * call sites do from a `DisposableEffect`'s `onDispose`.
     */
    fun setImmersive(active: Boolean) {
        activeCount = if (active) activeCount + 1 else (activeCount - 1).coerceAtLeast(0)
        _isActive.value = activeCount > 0
    }

    /** Test hook — drops any leaked counts between cases. */
    fun resetForTest() {
        activeCount = 0
        _isActive.value = false
    }
}
