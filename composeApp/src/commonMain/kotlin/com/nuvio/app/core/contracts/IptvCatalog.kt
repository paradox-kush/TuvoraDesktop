package com.nuvio.app.core.contracts

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Neutral read port over the IPTV account catalog (seam S3a). Lives in core/contracts — NOT under
 * features/iptv — so shared code depends on it without a firewall crossing (R2b), and exposes only
 * neutral capabilities, never the iptv-owned XtreamAccount type (R2c). The write side
 * (add/edit/remove) is a separate port (S3b, IptvAccountManager).
 */
interface IptvCatalog {
    /** Warm the account list (idempotent). */
    fun ensureLoaded()

    /** True while at least one account is enabled. */
    fun hasEnabledAccounts(): Boolean

    /** Count of enabled accounts. */
    val enabledAccountCount: Int

    /** Warm the TMDB<->stream match indexes off the critical path. */
    fun warmUpMatchIndexes(startDelayMs: Long)

    /** Refresh playlists whose auto-refresh interval is due (iOS foreground path). */
    suspend fun refreshDuePlaylists()
}

/** Compose provision (root-provided in FeatureWiring). */
val LocalIptvCatalog = staticCompositionLocalOf<IptvCatalog> {
    error("IptvCatalog not provided — wire it in FeatureWiring.installFeatures")
}

/**
 * Non-Compose access for object-singleton consumers that cannot read a CompositionLocal. Populated
 * once at process init (registerFeatureContributions). A single, neutral, explicit access point —
 * consumers name THIS, never the fork impl, so the firewall holds.
 */
object IptvCatalogAccess {
    private var instance: IptvCatalog? = null
    val catalog: IptvCatalog
        get() = instance ?: error("IptvCatalog not registered — see FeatureWiring.registerFeatureContributions")
    fun register(catalog: IptvCatalog) { instance = catalog }
}
