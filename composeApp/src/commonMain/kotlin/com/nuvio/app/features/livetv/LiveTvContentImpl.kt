package com.nuvio.app.features.livetv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.contracts.LiveTvContent
import com.nuvio.app.features.player.LiveReplayLaunch

internal object LiveTvContentImpl : LiveTvContent {
    @Composable
    override fun Render(
        initialContentId: String,
        initialTitle: String,
        initialLogo: String?,
        initialReplay: LiveReplayLaunch?,
        onBack: () -> Unit,
        modifier: Modifier,
    ) {
        LiveTvScreen(
            initialContentId = initialContentId,
            initialTitle = initialTitle,
            initialLogo = initialLogo,
            initialReplay = initialReplay,
            onBack = onBack,
            modifier = modifier,
        )
    }
}
