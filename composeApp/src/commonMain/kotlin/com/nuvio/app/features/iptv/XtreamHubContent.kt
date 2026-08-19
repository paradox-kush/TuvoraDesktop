package com.nuvio.app.features.iptv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.contracts.IptvHubContent
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.flow.Flow

internal object XtreamHubContent : IptvHubContent {
    @Composable
    override fun Render(
        modifier: Modifier,
        onPosterClick: (MetaPreview) -> Unit,
        onPlayLiveChannel: (String) -> Unit,
        onFavoriteLiveChannel: (String) -> Unit,
        onAddProvider: () -> Unit,
        scrollToTopRequests: Flow<Unit>,
    ) {
        XtreamHubScreen(
            modifier = modifier,
            onPosterClick = onPosterClick,
            onPlayLiveChannel = onPlayLiveChannel,
            onFavoriteLiveChannel = onFavoriteLiveChannel,
            onAddProvider = onAddProvider,
            scrollToTopRequests = scrollToTopRequests,
        )
    }
}
