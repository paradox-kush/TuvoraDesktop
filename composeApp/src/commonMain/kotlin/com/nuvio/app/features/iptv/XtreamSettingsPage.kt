package com.nuvio.app.features.iptv

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Desktop IPTV settings (Step 2): master-detail, no popup. The playlist list is on the left, one pane on the
 * right — a playlist's details, the "Add a playlist" page (setup code first, four manual routes below), or the
 * setup preview. See [IptvMasterDetail]. "Add a playlist" manual routes and "Edit server and login" open the
 * existing form page ([SettingsPage.IptvAddPlaylist]); content settings keep their own page.
 */
internal fun LazyListScope.xtreamSettingsContent(
    isTablet: Boolean,
    state: XtreamUiState,
    onAddManual: (XtreamSourceType) -> Unit = {},
    onEditPlaylist: (XtreamAccount) -> Unit = {},
    onOpenContent: (XtreamAccount) -> Unit = {},
) {
    item {
        // The guide mirror indexes every region it can find, but a household uses a fraction of it (2,035 of 15,397
        // channels on a measured panel). Unselected regions are never stored, so this trims the on-device index.
        var showRegionPicker by remember { mutableStateOf(false) }
        var regionSummary by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(showRegionPicker) {
            if (!showRegionPicker) {
                regionSummary = BoundedLoad.run(LoadSurface.SETTINGS, report = mapOf("row" to "epg_regions")) {
                    com.nuvio.app.features.epg.epgRegionSummary(
                        selected = com.nuvio.app.features.epg.EpgMirrorRepository.selectedRegions(),
                        available = com.nuvio.app.features.epg.EpgMirrorRepository.availableRegions(),
                    )
                }.valueOrNull() ?: "Unavailable"
            }
        }
        IptvMasterDetail(
            state = state,
            onAddManual = onAddManual,
            onEditPlaylist = onEditPlaylist,
            onOpenContent = onOpenContent,
            onGuideRegions = { showRegionPicker = true },
            guideRegionsSummary = regionSummary,
            // The two panes scroll on their own, so the item takes the viewport's height (the header above it
            // scrolls away a little; both panes stay fully reachable).
            modifier = Modifier.fillParentMaxHeight(),
        )
        if (showRegionPicker) {
            com.nuvio.app.features.epg.EpgRegionPickerHost(onDismiss = { showRegionPicker = false })
        }
    }
}
