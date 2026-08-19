package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.IptvContentClassifier
import com.nuvio.app.features.iptv.match.XtreamStreamSource

/** Fork-side impl of the neutral IptvContentClassifier (delegates to the iptv singletons). */
internal object XtreamContentClassifier : IptvContentClassifier {
    override fun isLiveId(id: String): Boolean = XtreamItemRegistry.isLiveId(id)
    override fun isOrphaned(id: String): Boolean = XtreamItemRegistry.isOrphaned(id)
    override fun isXtreamId(id: String): Boolean = XtreamItemRegistry.isXtreamId(id)
    override fun posterFor(id: String): String? = XtreamItemRegistry.get(id)?.let { it.poster ?: it.logo }
    override fun isXtreamStreamGroup(addonId: String): Boolean =
        addonId.startsWith(XtreamStreamSource.GROUP_ID_PREFIX)
}
