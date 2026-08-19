package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.LiveChannelInfo
import com.nuvio.app.core.contracts.LivePlaybackProvider
import com.nuvio.app.features.home.MetaPreview

/**
 * Fork-side [LivePlaybackProvider]: the live-TV launch resolution that used to live inline in App.kt.
 * Pure delegation to XtreamItemRegistry — behaviour identical to the code it replaced.
 */
internal object XtreamLivePlaybackProvider : LivePlaybackProvider {
    override fun accountNameFor(contentId: String): String? =
        XtreamItemRegistry.accountNameFor(contentId)

    override fun liveStreamUrlFor(contentId: String): String? =
        XtreamItemRegistry.liveStreamUrlFor(contentId)

    override suspend fun liveStreamUrlForAsync(contentId: String): String? =
        XtreamItemRegistry.liveStreamUrlForAsync(contentId)

    override fun channelInfoFor(contentId: String): LiveChannelInfo? =
        XtreamItemRegistry.get(contentId)?.let {
            LiveChannelInfo(name = it.name, logo = it.logo, poster = it.poster, streamUrl = it.streamUrl)
        }

    override fun recordingPreview(contentId: String): MetaPreview? =
        XtreamItemRegistry.get(contentId)?.toMetaPreview()
}
