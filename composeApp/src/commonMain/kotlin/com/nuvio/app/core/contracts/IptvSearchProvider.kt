package com.nuvio.app.core.contracts

import com.nuvio.app.features.home.HomeCatalogSection

/** Neutral IPTV search port (seam: search firewall). Returns shared HomeCatalogSection rows. */
interface IptvSearchProvider {
    suspend fun search(query: String): List<HomeCatalogSection>
}

object IptvSearchAccess {
    private var instance: IptvSearchProvider? = null
    val provider: IptvSearchProvider
        get() = instance ?: error("IptvSearchProvider not registered — see FeatureWiring")
    fun register(provider: IptvSearchProvider) { instance = provider }
}
