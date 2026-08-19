package com.nuvio.app.features.radar

import androidx.compose.runtime.Composable
import com.nuvio.app.core.contracts.HomeSportsSection
import com.nuvio.app.core.contracts.SportsReplay
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.toMetaPreview

/**
 * Fork-side [HomeSportsSection]: renders [RadarHomeSection] and owns the Xtream lookup that turns a
 * tapped recording id into a home poster. Registered by FeatureWiring so the shared home screen
 * hosts the sports rail without importing radar or IPTV.
 */
internal object RadarHomeSportsSection : HomeSportsSection {
    override fun ensureLoaded() {
        RadarRepository.ensureLoaded()
    }

    @Composable
    override fun Render(
        onOpenSportsTab: () -> Unit,
        onPlayChannel: (String) -> Unit,
        onAddPlaylist: () -> Unit,
        onOpenRecordingPoster: (MetaPreview) -> Unit,
        onPlayReplay: (SportsReplay) -> Unit,
    ) {
        RadarHomeSection(
            onOpenSportsTab = onOpenSportsTab,
            onPlayChannel = onPlayChannel,
            onAddPlaylist = onAddPlaylist,
            onOpenRecording = { id ->
                XtreamItemRegistry.get(id)?.toMetaPreview()?.let(onOpenRecordingPoster)
            },
            onPlayReplay = onPlayReplay,
        )
    }
}
