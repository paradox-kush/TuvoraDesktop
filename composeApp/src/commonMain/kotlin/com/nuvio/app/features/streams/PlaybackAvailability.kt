package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import com.nuvio.app.core.contracts.IptvCatalogAccess
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginsUiState

internal fun AddonManifest.supportsStream(type: String, videoId: String): Boolean =
    resources.any { resource ->
        resource.name == "stream" &&
            resource.types.contains(type) &&
            (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { videoId.startsWith(it) })
    }

internal fun hasCompatiblePlaybackSource(
    addons: List<ManagedAddon>,
    plugins: PluginsUiState,
    type: String,
    videoId: String,
): Boolean = addons.any { it.enabled && it.manifest?.supportsStream(type, videoId) == true } ||
    (plugins.pluginsEnabled && plugins.scrapers.any { it.enabled && it.supportsType(type) })

internal class PlaybackAvailability(
    private val addons: List<ManagedAddon>,
    private val plugins: PluginsUiState,
    /**
     * Fork: Stremio content types the user's IPTV accounts serve through the IPTV source lane — a
     * source like a scraper, so an IPTV-only user can still play catalog titles.
     */
    private val iptvSourceTypes: Set<String> = emptySet(),
) {
    fun canStream(type: String, videoId: String): Boolean =
        isIptvId(videoId) ||
            type in iptvSourceTypes ||
            hasCompatiblePlaybackSource(addons, plugins, type, videoId) ||
            MetaDetailsRepository.findEmbeddedStreams(videoId).isNotEmpty()

    fun canPlay(
        type: String,
        videoId: String,
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): Boolean = canStream(type, videoId) || DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        videoId = videoId,
    ) != null

    companion object {
        // Fork: IPTV items (Xtream/Stalker/M3U all carry "xtream:") are played by the IPTV resolver
        // directly and never need an addon or scraper. Without this, upstream's play-disable check
        // greys out Play/Resume for IPTV-only users (store builds hide addons). TV twin: 96324df71.
        private const val IPTV_ID_PREFIX = "xtream:"
        fun isIptvId(id: String): Boolean = id.startsWith(IPTV_ID_PREFIX)

        fun current(): PlaybackAvailability = PlaybackAvailability(
            addons = AddonRepository.uiState.value.addons,
            plugins = if (AppFeaturePolicy.pluginsEnabled) {
                PluginRepository.uiState.value
            } else {
                PluginsUiState(pluginsEnabled = false)
            },
            iptvSourceTypes = IptvCatalogAccess.catalogOrNull?.servedStreamTypes?.value.orEmpty(),
        )
    }
}

@Composable
internal fun rememberPlaybackAvailability(): PlaybackAvailability {
    val addons by remember {
        AddonRepository.initialize()
        AddonRepository.uiState
    }.collectAsStateWithLifecycle()
    val plugins = if (AppFeaturePolicy.pluginsEnabled) {
        val state by remember {
            PluginRepository.initialize()
            PluginRepository.uiState
        }.collectAsStateWithLifecycle()
        state
    } else {
        PluginsUiState(pluginsEnabled = false)
    }
    val downloads by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val iptvSourceTypes by remember {
        IptvCatalogAccess.catalogOrNull?.servedStreamTypes ?: MutableStateFlow(emptySet())
    }.collectAsStateWithLifecycle()
    return remember(addons, plugins, downloads, iptvSourceTypes) {
        PlaybackAvailability(addons.addons, plugins, iptvSourceTypes)
    }
}
