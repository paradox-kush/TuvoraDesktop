package com.nuvio.app.features.addons

import com.nuvio.app.features.streams.StreamItem

/**
 * Store builds ([com.nuvio.app.core.build.AppFeaturePolicy.addonStreamSourcesEnabled] = false) use
 * add-ons for discovery only — catalogs, metadata, subtitles — never as playback sources. Add-ons still
 * sync onto a store-build device (they are account data, edited on tuvora.co or a full build), so the
 * rule is applied where add-on content enters the app rather than by hiding the add-on list:
 *  - a manifest loses its `stream` resource, so no stream request is ever made to it;
 *  - streams embedded in add-on metadata are dropped (IPTV episodes ride the same field and stay);
 *  - a cached link from earlier add-on playback is never replayed.
 */
object AddonSourcePolicy {
    const val STREAM_RESOURCE = "stream"

    fun manifestForBuild(manifest: AddonManifest, streamSourcesEnabled: Boolean): AddonManifest =
        if (streamSourcesEnabled || manifest.resources.none { it.name == STREAM_RESOURCE }) {
            manifest
        } else {
            manifest.copy(resources = manifest.resources.filterNot { it.name == STREAM_RESOURCE })
        }

    fun embeddedStreamsForBuild(
        streams: List<StreamItem>,
        streamSourcesEnabled: Boolean,
        isIptv: (String?) -> Boolean,
    ): List<StreamItem> = if (streamSourcesEnabled) streams else streams.filter { isIptv(it.addonId) }

    fun cachedLinkUsable(addonId: String?, streamSourcesEnabled: Boolean, isIptv: (String?) -> Boolean): Boolean =
        streamSourcesEnabled || isIptv(addonId)
}
