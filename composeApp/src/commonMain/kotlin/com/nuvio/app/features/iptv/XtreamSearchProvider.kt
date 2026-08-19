package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.IptvSearchProvider
import com.nuvio.app.features.home.HomeCatalogSection

internal object XtreamSearchProvider : IptvSearchProvider {
    override suspend fun search(query: String): List<HomeCatalogSection> = XtreamSearchIndex.search(query)
}
