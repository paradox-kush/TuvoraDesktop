package com.nuvio.app.core.contracts

import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Recently-watched live channels, as home-ready previews. Live playback records no watch progress,
 * so this feeds the Live TV row of the split Continue Watching UI. The fork (Xtream) owns the store
 * and the XtreamLiveRecent -> MetaPreview mapping; the shared home screen consumes neutral previews
 * and never imports the IPTV subsystem. No-op default: an empty flow when IPTV is absent.
 */
interface LiveRecentsProvider {
    val previews: StateFlow<List<MetaPreview>>
    fun ensureLoaded()
    fun remove(contentId: String)
}

object LiveRecentsAccess {
    private val noOp = object : LiveRecentsProvider {
        override val previews: StateFlow<List<MetaPreview>> = MutableStateFlow(emptyList())
        override fun ensureLoaded() {}
        override fun remove(contentId: String) {}
    }
    private var provider: LiveRecentsProvider? = null

    fun register(p: LiveRecentsProvider) {
        provider = p
    }

    /** The registered provider, or a no-op empty one until IPTV registers. */
    fun current(): LiveRecentsProvider = provider ?: noOp

    fun resetForTest() {
        provider = null
    }
}
