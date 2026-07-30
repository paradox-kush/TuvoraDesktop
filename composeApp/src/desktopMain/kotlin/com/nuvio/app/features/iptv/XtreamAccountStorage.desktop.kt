package com.nuvio.app.features.iptv

import com.nuvio.app.core.storage.DesktopStorage

internal actual object XtreamAccountStorage {
    private val store by lazy { DesktopStorage.store("nuvio_iptv") }

    actual fun loadAccountsJson(profileId: Int): String? = store.getString("xtream_accounts_$profileId")

    actual fun saveAccountsJson(profileId: Int, json: String) {
        store.putString("xtream_accounts_$profileId", json)
    }

    actual fun loadRecentsJson(profileId: Int): String? = store.getString("xtream_live_recents_$profileId")

    actual fun saveRecentsJson(profileId: Int, json: String) {
        store.putString("xtream_live_recents_$profileId", json)
    }

    actual fun loadRadarJson(profileId: Int): String? = store.getString("radar_state_$profileId")

    actual fun saveRadarJson(profileId: Int, json: String) {
        store.putString("radar_state_$profileId", json)
    }

    actual fun loadRadarFixturesJson(profileId: Int): String? = store.getString("radar_fixtures_$profileId")

    actual fun saveRadarFixturesJson(profileId: Int, json: String) {
        store.putString("radar_fixtures_$profileId", json)
    }

    actual fun loadRefreshStateJson(profileId: Int): String? = store.getString("xtream_refresh_state_$profileId")

    actual fun saveRefreshStateJson(profileId: Int, json: String) {
        store.putString("xtream_refresh_state_$profileId", json)
    }
}
