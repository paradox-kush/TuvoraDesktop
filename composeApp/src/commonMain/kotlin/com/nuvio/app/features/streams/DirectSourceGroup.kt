package com.nuvio.app.features.streams

/**
 * The single group of a direct own-source lane: an id an own source handles (IPTV VOD/live, a media-server
 * item) resolves to exactly one stream, and the group id is the one that STREAM carries - lifted from
 * the resolved [StreamItem], never a literal. It feeds `StreamLinkCacheRepository.save` and
 * `PlayerLaunch.providerAddonId`, so a second source gets its own group id for free.
 */
internal fun StreamItem.asDirectSourceGroup(): AddonStreamGroup = AddonStreamGroup(
    addonName = addonName,
    addonId = addonId,
    streams = listOf(this),
    isLoading = false,
)
