package com.nuvio.app.features.iptv

import com.nuvio.app.core.storage.DesktopStorage

internal actual object XtreamAccountStorage {
    private val realStore by lazy { DesktopStorage.store("nuvio_iptv") }

    /**
     * Test seam: a store on a temp file. Desktop storage is the REAL ~/Library/Application Support/Tuvora,
     * so a test that drives the hub (which remembers its selection here) must never reach [realStore].
     */
    internal var storeOverrideForTests: DesktopStorage.Store? = null

    private val store: DesktopStorage.Store get() = storeOverrideForTests ?: realStore

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

    actual fun loadRadarCatalogJson(profileId: Int): String? = store.getString("radar_catalog_$profileId")
    actual fun saveRadarCatalogJson(profileId: Int, json: String) {
        store.putString("radar_catalog_$profileId", json)
    }

    actual fun loadRefreshStateJson(profileId: Int): String? = store.getString("xtream_refresh_state_$profileId")

    actual fun saveRefreshStateJson(profileId: Int, json: String) {
        store.putString("xtream_refresh_state_$profileId", json)
    }

    actual fun loadHubSelectionJson(profileId: Int): String? = store.getString("xtream_hub_selection_$profileId")

    actual fun saveHubSelectionJson(profileId: Int, json: String) {
        store.putString("xtream_hub_selection_$profileId", json)
    }

    actual fun loadPlaylistSyncStateJson(profileId: Int): String? = store.getString("xtream_sync_state_$profileId")

    actual fun savePlaylistSyncStateJson(profileId: Int, json: String) {
        store.putString("xtream_sync_state_$profileId", json)
    }

    actual fun loadServerFailoverJson(profileId: Int): String? = store.getString("xtream_server_failover_$profileId")

    actual fun saveServerFailoverJson(profileId: Int, json: String) {
        store.putString("xtream_server_failover_$profileId", json)
    }

    actual fun loadManagedInfoJson(profileId: Int): String? = store.getString("xtream_managed_info_$profileId")

    actual fun saveManagedInfoJson(profileId: Int, json: String) {
        store.putString("xtream_managed_info_$profileId", json)
    }
}
