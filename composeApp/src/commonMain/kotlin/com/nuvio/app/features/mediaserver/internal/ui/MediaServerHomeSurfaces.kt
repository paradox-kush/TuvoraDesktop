package com.nuvio.app.features.mediaserver.internal.ui

import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import kotlinx.coroutines.launch

/**
 * What a change to a server (a sign-in, a row switched on, a removal) must tell the rest of the app: the Home
 * contributor's cached rows are stale, the Home layout list must re-read which rows exist, and Home re-pulls its
 * contributed rows (TTL-gated by the contributor - a change invalidated it, so this is one fetch, not a loop).
 */
internal object MediaServerHomeSurfaces {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    fun changed(runtime: MediaServerRuntime, entry: MediaServerEntry) {
        val contributor = runtime.homeContributor
        contributor?.invalidate(entry.sourceKey)
        scope.launch {
            // localized row titles first (resource reads are suspending), then the layout list, then the rows themselves
            contributor?.prepareDeclaredRows()
            HomeCatalogSettingsRepository.syncContributed()
            HomeRepository.refreshContributed()
        }
    }
}
