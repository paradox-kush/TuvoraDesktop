package com.nuvio.app.features.iptv

import com.nuvio.app.core.build.AppBuildConfig
import com.nuvio.app.core.network.SupabaseConfig

/**
 * B24 — gates the v2 revision-contract sync path. It activates the REAL shipping integration
 * (the same repositories, sync service and reconcile core) but only under a debug build pointing at
 * a LOCAL/dev backend. A release build always targets the hosted supabase.co endpoint, so
 * [v2Enabled] is false there and the app keeps the v1 path until the v2 backend is deployed and this
 * gate is widened deliberately. This is the "debug configuration may select the local v2 backend"
 * requirement — the local selection can never enter a release build because the endpoint check fails.
 */
internal object PlaylistSyncConfig {
    val v2Enabled: Boolean by lazy {
        AppBuildConfig.IS_DEBUG_BUILD && isLocalOrDevEndpoint(SupabaseConfig.URL)
    }

    private fun isLocalOrDevEndpoint(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.contains("10.0.2.2") ||       // Android emulator host loopback
            u.contains("127.0.0.1") ||
            u.contains("://localhost") ||
            u.contains("://192.168.") ||        // LAN dev host
            u.contains("://10.")                // private range dev host
    }
}
