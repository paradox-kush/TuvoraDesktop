package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.ContentClassifierRegistry
import com.nuvio.app.core.contracts.MetaSourceRegistry
import com.nuvio.app.core.contracts.OwnSourcePolicy
import com.nuvio.app.core.contracts.SearchProviderRegistry
import com.nuvio.app.core.contracts.StreamSourceRegistry
import com.nuvio.app.features.iptv.match.XtreamStreamSource

/**
 * IPTV's id namespaces, as the neutral [OwnSourcePolicy] predicates: the content ids every
 * Xtream/Stalker/M3U item plays under ("xtream:{account}:{kind}:{id}") and the stream-provider ids of
 * its two lanes - the direct lane ("xtream") and the TMDB-matched lane ("xtream-match:{account}").
 */
internal object XtreamOwnSource {
    fun isOwnContentId(id: String): Boolean = XtreamItemRegistry.isXtreamId(id)

    fun isOwnProviderId(addonId: String): Boolean =
        addonId == XtreamItemRegistry.DIRECT_GROUP_ID || addonId.startsWith(XtreamStreamSource.GROUP_ID_PREFIX)
}

/**
 * IPTV registers into every plural source port as ONE entry each (media-servers design 5.1). The one
 * place that lists them, so the production wiring ([com.nuvio.app.registerLogicFeatureContributions])
 * and the golden-list contract test wire exactly the same set. Call once per process.
 */
internal object IptvSourceRegistrations {
    const val NAME = "iptv"

    fun register() {
        ContentClassifierRegistry.register(NAME, XtreamContentClassifier)
        StreamSourceRegistry.register(NAME, XtreamStreamSourceProvider)
        MetaSourceRegistry.register(NAME, XtreamMetaSource)
        SearchProviderRegistry.register(NAME, XtreamSearchProvider)
        OwnSourcePolicy.registerContentIdPredicate(NAME, XtreamOwnSource::isOwnContentId)
        OwnSourcePolicy.registerProviderIdPredicate(NAME, XtreamOwnSource::isOwnProviderId)
    }
}
