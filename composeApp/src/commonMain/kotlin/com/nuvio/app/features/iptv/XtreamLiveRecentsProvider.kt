package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.LiveRecentsProvider
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Fork-side [LiveRecentsProvider]: maps the Xtream live-recents store to home previews. Registered
 * by FeatureWiring so the shared home screen never imports XtreamLiveRecents. The mapping that used
 * to run inline in the composable now runs here, once, in a held scope.
 */
internal object XtreamLiveRecentsProvider : LiveRecentsProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val previews: StateFlow<List<MetaPreview>> =
        XtreamLiveRecents.recents
            .map { recents -> recents.map { it.toMetaPreview() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override fun ensureLoaded() {
        XtreamLiveRecents.ensureLoaded()
    }

    override fun remove(contentId: String) {
        XtreamLiveRecents.remove(contentId)
    }
}
