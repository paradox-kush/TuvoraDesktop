package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.contracts.ContentClassifierRegistry
import com.nuvio.app.core.contracts.HomeSectionContributorRegistry
import com.nuvio.app.core.contracts.MetaSourceRegistry
import com.nuvio.app.core.contracts.OwnSourcePolicy
import com.nuvio.app.core.contracts.PlaybackResumeOfferRegistry
import com.nuvio.app.core.contracts.PlaybackSessionReporterRegistry
import com.nuvio.app.core.contracts.SearchProviderRegistry
import com.nuvio.app.core.contracts.StreamSourceRegistry
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.mediaserver.internal.policy.ServerAudioChoicePolicy
import com.nuvio.app.features.player.DeviceLanguagePreferences
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.languageMatchesPreference
import com.nuvio.app.features.player.resolvePreferredAudioLanguageTargets

/**
 * Media servers register into every plural source port as ONE entry each (design 5.1) under the name
 * `mediaserver`, plus the own-source id predicates (`ms:` content ids; `ms` / `ms-match:` provider ids), the
 * Home contributor and the playback-session reporter. The one place that lists them: the production wiring
 * ([com.nuvio.app.registerLogicFeatureContributions]) and the contract test wire exactly the same set. Call
 * once per process (a duplicate name is refused by the registries).
 */
internal object MediaServerSourceRegistrations {
    const val NAME = "mediaserver"

    /** Tuvora's own audio-language setting, read at mint time (owner decision 2026-10-06; see [ServerAudioChoicePolicy]). */
    private fun playerAudioPreference() = ServerAudioChoicePolicy.Preference(
        languages = {
            PlayerSettingsRepository.ensureLoaded()
            val settings = PlayerSettingsRepository.uiState.value
            resolvePreferredAudioLanguageTargets(
                preferredAudioLanguage = settings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
                deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
            )
        },
        matches = ::languageMatchesPreference,
    )

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
        StreamSourceRegistry.register(
            NAME,
            MediaServerStreamSourceProvider(store, services, MediaServerMatchLane(store, services, runtime.nowMs, TmdbMatchTitleFacts), audioPreference = playerAudioPreference(), onMintFailure = MediaServerMintNotices::show),
        )
        MetaSourceRegistry.register(NAME, MediaServerMetaSource(store, services))
        SearchProviderRegistry.register(NAME, MediaServerSearchProvider(store, services))
        OwnSourcePolicy.registerContentIdPredicate(NAME, MediaServerIds::isOwnContentId)
        OwnSourcePolicy.registerProviderIdPredicate(NAME, MediaServerIds::isOwnProviderId)
        // v1: a server's own items are never scrobbled to Trakt/Simkl/MDBList (owner decision 2026-10-06).
        OwnSourcePolicy.registerScrobbleExclusion(NAME, MediaServerIds::isContentId)
        // ...and no event leaving the device may name the server or the user: telemetry gets the salted hash form.
        OwnSourcePolicy.registerTelemetryRewriter(NAME) { id, salt -> MediaServerIds.parse(id)?.let { MediaServerIds.telemetryId(it, salt) } }
        HomeSectionContributorRegistry.register(home)
        PlaybackResumeOfferRegistry.register(MediaServerResumeOffers(store, services))
        PlaybackSessionReporterRegistry.register(
            MediaServerSessionReporter(store, services, runtime.nowMs, onReported = { sourceKey -> home.invalidate(sourceKey) }),
        )
    }
}
