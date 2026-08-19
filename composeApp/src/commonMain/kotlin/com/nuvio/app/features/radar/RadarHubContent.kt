package com.nuvio.app.features.radar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.contracts.SportsHubContent
import com.nuvio.app.core.contracts.SportsReplay

internal object RadarHubContent : SportsHubContent {
    @Composable
    override fun Render(
        modifier: Modifier,
        onPlayChannel: (String) -> Unit,
        onAddPlaylist: () -> Unit,
        onOpenRecording: (String) -> Unit,
        onPlayReplay: (SportsReplay) -> Unit,
    ) {
        SportsHubScreen(
            modifier = modifier,
            onPlayChannel = onPlayChannel,
            onAddPlaylist = onAddPlaylist,
            onOpenRecording = onOpenRecording,
            onPlayReplay = onPlayReplay,
        )
    }
}
