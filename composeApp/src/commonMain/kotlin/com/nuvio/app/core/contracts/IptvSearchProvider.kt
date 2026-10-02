package com.nuvio.app.core.contracts

import com.nuvio.app.features.home.HomeCatalogSection
import kotlinx.coroutines.flow.Flow

/** Neutral IPTV search port (seam: search firewall). Returns shared HomeCatalogSection rows. */
interface IptvSearchProvider {
    suspend fun search(query: String): List<HomeCatalogSection>

    /**
     * UX15: an opaque fingerprint of everything that changes what [search] can return — which
     * playlists are enabled, their content types and category selections, and the channels and
     * groups the viewer hid. Null while no playlist is enabled. Equal values mean equal results, so a
     * shown search is refreshed only when this changes. A digest: never carries credentials.
     */
    fun sourceSignature(): String?

    /** [sourceSignature] now, and again on every change (distinct values only). */
    fun sourceSignatureChanges(): Flow<String?>
}

object IptvSearchAccess {
    private var instance: IptvSearchProvider? = null
    val provider: IptvSearchProvider
        get() = instance ?: error("IptvSearchProvider not registered — see FeatureWiring")
    fun register(provider: IptvSearchProvider) { instance = provider }

    /** The registered provider, or null before registration (tests, previews). */
    val providerOrNull: IptvSearchProvider? get() = instance

    /** Tests that registered a fake put the process back as they found it. */
    internal fun unregisterForTest() { instance = null }
}
