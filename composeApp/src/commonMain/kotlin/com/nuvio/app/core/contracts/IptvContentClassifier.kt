package com.nuvio.app.core.contracts

/**
 * Neutral content-classification port (seams S5-E / S7 prep). Lets shared code (WatchProgress rules,
 * stream-cache grouping) classify content without naming features/iptv. Pure reads, no iptv types.
 */
interface IptvContentClassifier {
    fun isLiveId(id: String): Boolean
    fun isOrphaned(id: String): Boolean
    fun isXtreamId(id: String): Boolean
    /** Poster (or logo fallback) for an iptv content id, or null. */
    fun posterFor(id: String): String?
    /** True when an addon group id is an xtream stream group. */
    fun isXtreamStreamGroup(addonId: String): Boolean
}

object IptvContentClassifierAccess {
    private var instance: IptvContentClassifier? = null
    val classifier: IptvContentClassifier
        get() = instance ?: error("IptvContentClassifier not registered — see FeatureWiring")
    fun register(classifier: IptvContentClassifier) { instance = classifier }
}
