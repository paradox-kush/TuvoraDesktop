package com.nuvio.app.core.contracts

import com.nuvio.app.features.home.MetaPreview

/** A live channel's display + playback fields, as the registry holds them. */
data class LiveChannelInfo(
    val name: String,
    val logo: String?,
    val poster: String?,
    val streamUrl: String?,
)

/**
 * Firewall port for the App.kt live-TV launch. The fork resolves live URLs and channel identity;
 * App.kt navigates. No-op default (nulls) so a build without IPTV simply has no live channels —
 * the launch paths short-circuit exactly as they did when the registry returned nothing.
 */
interface LivePlaybackProvider {
    fun accountNameFor(contentId: String): String?
    fun liveStreamUrlFor(contentId: String): String?
    suspend fun liveStreamUrlForAsync(contentId: String): String?
    /** name/logo/poster/streamUrl for a registered channel id, or null. */
    fun channelInfoFor(contentId: String): LiveChannelInfo?
    /** Home poster for a registered recording (VOD) id, or null. */
    fun recordingPreview(contentId: String): MetaPreview?
}

object LivePlaybackAccess {
    private val noOp = object : LivePlaybackProvider {
        override fun accountNameFor(contentId: String): String? = null
        override fun liveStreamUrlFor(contentId: String): String? = null
        override suspend fun liveStreamUrlForAsync(contentId: String): String? = null
        override fun channelInfoFor(contentId: String): LiveChannelInfo? = null
        override fun recordingPreview(contentId: String): MetaPreview? = null
    }
    private var provider: LivePlaybackProvider? = null

    fun register(p: LivePlaybackProvider) {
        provider = p
    }

    fun current(): LivePlaybackProvider = provider ?: noOp

    fun resetForTest() {
        provider = null
    }
}
