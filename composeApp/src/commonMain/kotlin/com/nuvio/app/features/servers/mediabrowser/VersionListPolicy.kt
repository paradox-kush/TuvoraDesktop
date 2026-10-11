package com.nuvio.app.features.servers.mediabrowser

/**
 * Fork policy (ported from the retired fork features/mediaserver VersionPickPolicy/MediaServerVersions): decides whether an item
 * page's MediaSources are only on-demand stand-ins, so the real version list has to be asked for.
 */
internal object VersionListPolicy {
    /** True when the item lists sources, and every one of them is a Placeholder. */
    fun needsPlaybackInfo(sources: List<MediaSource>): Boolean =
        sources.isNotEmpty() && sources.all { it.isPlaceholder }
}
