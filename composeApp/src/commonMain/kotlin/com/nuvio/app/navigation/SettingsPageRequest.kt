package com.nuvio.app.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Settings page a cross-tab shortcut wants opened (for example "Add IPTV provider" from the
 * IPTV tab). iOS renders Settings in its own Compose instance — a full-screen cover, because the
 * native tab bar has no Settings slot — so the request cannot ride along in the requesting
 * instance's local state and has to live somewhere both instances can see.
 */
object SettingsPageRequest {
    private val _pageName = MutableStateFlow<String?>(null)
    val pageName: StateFlow<String?> = _pageName.asStateFlow()

    fun request(name: String) {
        _pageName.value = name
    }

    fun consume() {
        _pageName.value = null
    }
}
