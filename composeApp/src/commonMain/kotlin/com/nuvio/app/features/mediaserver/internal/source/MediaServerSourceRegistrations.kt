package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.contracts.ContentClassifierRegistry
import com.nuvio.app.core.contracts.HomeSectionContributorRegistry
import com.nuvio.app.core.contracts.MetaSourceRegistry
import com.nuvio.app.core.contracts.OwnSourcePolicy
import com.nuvio.app.core.contracts.PlaybackSessionReporterRegistry
import com.nuvio.app.core.contracts.SearchProviderRegistry
import com.nuvio.app.core.contracts.StreamSourceRegistry
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.watchprogress.WatchProgressRepository

/**
 * Media servers register into every plural source port as ONE entry each (design 5.1) under the name
 * `mediaserver`, plus the own-source id predicates (`ms:` content ids; `ms` / `ms-match:` provider ids), the
 * Home contributor and the playback-session reporter. The one place that lists them: the production wiring
 * ([com.nuvio.app.registerLogicFeatureContributions]) and the contract test wire exactly the same set. Call
 * once per process (a duplicate name is refused by the registries).
 */
internal object MediaServerSourceRegistrations {
    const val NAME = "mediaserver"

    fun register(runtime: MediaServerRuntime = MediaServerRuntime.production) {
        val store = runtime.entryStore
        val services = runtime.services
        val home = MediaServerHomeContributor(
            store = store,
            services = services,
            nowMs = runtime.nowMs,
            tuvoraContinueWatchingIds = {
                WatchProgressRepository.uiState.value.entries.filter { !it.isEffectivelyCompleted }.map { it.parentMetaId }.toSet()
            },
        )
        runtime.homeContributor = home
        ContentClassifierRegistry.register(NAME, MediaServerClassifier(store))
        StreamSourceRegistry.register(NAME, MediaServerStreamSourceProvider(store, services))
        MetaSourceRegistry.register(NAME, MediaServerMetaSource(store, services))
        SearchProviderRegistry.register(NAME, MediaServerSearchProvider(store, services))
        OwnSourcePolicy.registerContentIdPredicate(NAME, MediaServerIds::isOwnContentId)
        OwnSourcePolicy.registerProviderIdPredicate(NAME, MediaServerIds::isOwnProviderId)
        HomeSectionContributorRegistry.register(home)
        PlaybackSessionReporterRegistry.register(
            MediaServerSessionReporter(store, services, runtime.nowMs, onReported = { sourceKey -> home.invalidate(sourceKey) }),
        )
    }
}
