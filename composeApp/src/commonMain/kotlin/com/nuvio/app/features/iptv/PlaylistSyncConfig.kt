package com.nuvio.app.features.iptv

import com.nuvio.app.core.build.AppBuildConfig
import com.nuvio.app.core.network.SupabaseConfig

/**
 * B24 — resolves WHETHER and HOW the v2 revision-contract sync path activates (Desktop twin of
 * NuvioMobile's). The per-profile decision, including "never downgrade an adopted profile to v1",
 * lives in the pure [PlaylistSyncActivationPolicy]; this object only supplies the inputs.
 */
internal object PlaylistSyncConfig {
    /**
     * Ship value. Decision 2026-09-27 (B60 RC3): ENABLED — reversing the 2026-09-11 scope-out. Since
     * the backend legacy-write guard deployed that day, a v1 push to a v2-adopted profile is a silent
     * no-op, so a v1 Desktop lost every playlist add/edit/delete for anyone on a 1.7.x phone or TV. The
     * backend guard enforces safety regardless of client, and an adopted profile never downgrades to
     * v1 (it pauses). Build-baked like Mobile: there is no fork-owned manifest host for an override.
     */
    internal val buildDefaultRollout: PlaylistV2Rollout = PlaylistV2Rollout.ENABLED

    val effectiveRollout: PlaylistV2Rollout get() = buildDefaultRollout

    /** A debug build pointing at a local/dev backend (keeps the developer inner loop on v2). */
    val isDebugLocalDev: Boolean by lazy {
        AppBuildConfig.IS_DEBUG_BUILD && isLocalOrDevEndpoint(SupabaseConfig.URL)
    }

    /** The sync path a profile should take, given whether it has already adopted v2 locally. */
    fun activationFor(profileHasAdoptedV2: Boolean): PlaylistSyncActivation =
        PlaylistSyncActivationPolicy.activation(effectiveRollout, isDebugLocalDev, profileHasAdoptedV2)

    /** True when this profile records durable pending ops (v2 active OR paused-after-adoption). */
    fun recordsPending(profileHasAdoptedV2: Boolean): Boolean =
        activationFor(profileHasAdoptedV2) != PlaylistSyncActivation.V1_LEGACY

    /** Would a FRESH (never-adopted) profile use v2 right now? Prefer [activationFor]. */
    val v2Enabled: Boolean
        get() = activationFor(profileHasAdoptedV2 = false) == PlaylistSyncActivation.V2_ACTIVE

    private fun isLocalOrDevEndpoint(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.contains("10.0.2.2") ||       // Android emulator host loopback
            u.contains("127.0.0.1") ||
            u.contains("://localhost") ||
            u.contains("://192.168.") ||        // LAN dev host
            u.contains("://10.")                // private range dev host
    }
}
