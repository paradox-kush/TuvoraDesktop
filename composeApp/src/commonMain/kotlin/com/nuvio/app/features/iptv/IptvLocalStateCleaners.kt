package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.LocalStateCleaner

internal object XtreamRecentsCleaner : LocalStateCleaner {
    override val name = "Xtream live recents"
    override fun clearLocalState() = XtreamLiveRecents.clearLocalState()
}

internal object XtreamAccountsCleaner : LocalStateCleaner {
    override val name = "Xtream accounts"
    override fun clearLocalState() = XtreamRepository.clearLocalState()
}
