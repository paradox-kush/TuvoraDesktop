package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun CoroutineScope.launchPlayerNextEpisodeAutoPlay(
    previousJob: Job?,
    nextEpisodeInfo: NextEpisodeInfo?,
    allEpisodes: List<MetaVideo>,
    parentMetaId: String,
    parentMetaType: String,
    contentType: String?,
    settings: PlayerSettingsUiState,
    currentStreamBingeGroup: String?,
    currentProviderAddonId: String?,
    currentLanguageTargets: Set<String>,
    contentOriginalLanguage: String?,
    // User-initiated (button/card tap) vs. automatic near-end trigger: manual skips the
    // countdown and may open the source picker; auto never surfaces a panel uninvited.
    manualTrigger: Boolean,
    onDownloadedEpisodeSelected: (DownloadItem, MetaVideo) -> Unit,
    onEpisodeStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onManualSelectionRequired: (MetaVideo) -> Unit,
    onSearchingChanged: (Boolean) -> Unit,
    onSourceNameChanged: (String?) -> Unit,
    onCountdownChanged: (Int?) -> Unit,
    onNextEpisodeCardVisibleChanged: (Boolean) -> Unit,
): Job? {
    val nextVideoId = nextEpisodeInfo?.videoId ?: return null
    val nextVideo = allEpisodes.firstOrNull { video -> video.id == nextVideoId } ?: return null
    if (nextEpisodeInfo.hasAired != true) return null

    val downloadedNextEpisode = DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = nextVideo.season,
        episodeNumber = nextVideo.episode,
        videoId = nextVideo.id,
    )
    if (downloadedNextEpisode != null) {
        onDownloadedEpisodeSelected(downloadedNextEpisode, nextVideo)
        return null
    }

    previousJob?.cancel()
    onSearchingChanged(true)
    onSourceNameChanged(null)
    onCountdownChanged(null)

    val type = contentType ?: parentMetaType
    // Users who configured an explicit auto-play mode keep their scoping/regex and its
    // pick-anything fallback; everyone else gets the tiered same-provider/same-language flow
    // that ends in the manual picker.
    val usesConfiguredAutoPlay = settings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL
    val effectiveMode = if (usesConfiguredAutoPlay) settings.streamAutoPlayMode else StreamAutoPlayMode.FIRST_STREAM
    val effectiveSource = if (usesConfiguredAutoPlay) settings.streamAutoPlaySource else StreamAutoPlaySource.ALL_SOURCES
    val effectiveSelectedAddons = if (usesConfiguredAutoPlay) settings.streamAutoPlaySelectedAddons else emptySet()
    val effectiveSelectedPlugins = if (usesConfiguredAutoPlay) settings.streamAutoPlaySelectedPlugins else emptySet()
    val effectiveRegex = if (usesConfiguredAutoPlay) settings.streamAutoPlayRegex else ""
    val preferredBingeGroup = if (settings.streamAutoPlayPreferBingeGroup) {
        currentStreamBingeGroup
    } else {
        null
    }

    return launch {
        PlayerStreamsRepository.loadEpisodeStreams(
            type = type,
            videoId = nextVideo.id,
            season = nextVideo.season,
            episode = nextVideo.episode,
        )

        val installedAddonNames = AddonRepository.uiState.value.addons
            .enabledAddons()
            .map { it.displayTitle }
            .toSet()
        val debridSettings = DebridSettingsRepository.snapshot()

        val timeoutSeconds = settings.streamAutoPlayTimeoutSeconds
        var autoSelectTriggered = false
        var timeoutElapsed = false
        var selectedStream: StreamItem? = null
        val autoSelectSettled = CompletableDeferred<Unit>()

        fun settleAutoSelect() {
            if (!autoSelectSettled.isCompleted) {
                autoSelectSettled.complete(Unit)
            }
        }

        fun selectStream(stream: StreamItem) {
            autoSelectTriggered = true
            selectedStream = stream
            settleAutoSelect()
        }

        fun finishWithoutSelection() {
            autoSelectTriggered = true
            settleAutoSelect()
        }

        fun trySelectStream(streams: List<StreamItem>, confidentTiersOnly: Boolean = false): StreamItem? {
            val candidates = StreamAutoPlaySelector.autoPlayCandidateStreams(
                streams = streams,
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonNames,
                selectedAddons = effectiveSelectedAddons,
                selectedPlugins = effectiveSelectedPlugins,
            )
            return PlayerNextEpisodeSourceSelector.select(
                streams = candidates,
                currentProviderAddonId = currentProviderAddonId,
                currentBingeGroup = preferredBingeGroup,
                currentLanguages = currentLanguageTargets,
                contentOriginalLanguage = contentOriginalLanguage,
                isReady = { stream ->
                    StreamAutoPlaySelector.isStreamReadyForAutoPlay(
                        stream = stream,
                        debridEnabled = debridSettings.canResolvePlayableLinks,
                        activeResolverProviderId = debridSettings.activeResolverProviderId,
                    )
                },
                allowAnyFallback = usesConfiguredAutoPlay && !confidentTiersOnly,
                confidentTiersOnly = confidentTiersOnly,
            )?.stream
        }

        val innerJob = launch {
            PlayerStreamsRepository.episodeStreamsState.collectLatest { state ->
                if (state.groups.isEmpty() && state.isAnyLoading) return@collectLatest

                val allStreams = state.groups.flatMap { it.streams }

                if (autoSelectTriggered) {
                    // Already resolved.
                } else if (timeoutElapsed) {
                    if (allStreams.isNotEmpty()) {
                        val candidate = trySelectStream(allStreams)
                        if (candidate != null) {
                            selectStream(candidate)
                        }
                    }
                } else if (allStreams.isNotEmpty()) {
                    // While providers are still answering, only grab high-confidence
                    // continuations (same provider or same release chain).
                    val earlyMatch = trySelectStream(allStreams, confidentTiersOnly = true)
                    if (earlyMatch != null) {
                        selectStream(earlyMatch)
                    }
                }

                if (!autoSelectTriggered && !state.isAnyLoading) {
                    if (allStreams.isNotEmpty()) {
                        val candidate = trySelectStream(allStreams)
                        if (candidate != null) {
                            selectStream(candidate)
                        }
                    }
                    if (!autoSelectTriggered) {
                        finishWithoutSelection()
                    }
                    return@collectLatest
                }

                if (autoSelectTriggered) return@collectLatest
            }
        }

        val timeoutMs = timeoutSeconds * 1_000L
        val isBoundedTimeout = timeoutSeconds in 1..30

        if (isBoundedTimeout) {
            delay(timeoutMs)
            timeoutElapsed = true
            if (!autoSelectTriggered) {
                val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                if (allStreams.isNotEmpty()) {
                    val candidate = trySelectStream(allStreams)
                    if (candidate != null) {
                        selectStream(candidate)
                    }
                }
            }
            if (selectedStream != null) {
                innerJob.cancel()
            } else if (PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }.isNotEmpty()) {
                innerJob.cancel()
                finishWithoutSelection()
            } else {
                val completed = withTimeoutOrNull(timeoutMs) { autoSelectSettled.await() }
                innerJob.cancel()
                if (completed == null && !autoSelectTriggered) {
                    val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                    if (allStreams.isNotEmpty()) {
                        selectedStream = trySelectStream(allStreams)
                    }
                    finishWithoutSelection()
                }
            }
        } else {
            timeoutElapsed = true
            if (!autoSelectTriggered) {
                val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                if (allStreams.isNotEmpty()) {
                    trySelectStream(allStreams)?.let(::selectStream)
                }
            }
            val completed = withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { autoSelectSettled.await() }
            innerJob.cancel()
            if (completed == null && !autoSelectTriggered) {
                val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                if (allStreams.isNotEmpty()) {
                    selectedStream = trySelectStream(allStreams)
                }
                finishWithoutSelection()
            }
        }

        onSearchingChanged(false)
        val selected = selectedStream
        if (selected != null) {
            onSourceNameChanged(selected.addonName)
            if (!manualTrigger) {
                for (i in 3 downTo 1) {
                    onCountdownChanged(i)
                    delay(1000)
                }
            }
            onEpisodeStreamSelected(selected, nextVideo)
            onNextEpisodeCardVisibleChanged(false)
            onCountdownChanged(null)
            onSourceNameChanged(null)
        } else if (manualTrigger) {
            onManualSelectionRequired(nextVideo)
            onNextEpisodeCardVisibleChanged(false)
        }
        // Auto flow with no confident match: keep the card up, quietly idle — tapping it
        // re-runs as a manual trigger and surfaces the source picker.
    }
}
