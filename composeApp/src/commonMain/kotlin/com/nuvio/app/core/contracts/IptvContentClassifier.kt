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

/** No iptv feature wired (tests, previews, IPTV-free builds) → nothing is iptv content. */
private object NoOpIptvContentClassifier : IptvContentClassifier {
    override fun isLiveId(id: String) = false
    override fun isOrphaned(id: String) = false
    override fun isXtreamId(id: String) = false
    override fun posterFor(id: String): String? = null
    override fun isXtreamStreamGroup(addonId: String) = false
}

object IptvContentClassifierAccess {
    private var instance: IptvContentClassifier = NoOpIptvContentClassifier
    val classifier: IptvContentClassifier get() = instance
    fun register(classifier: IptvContentClassifier) { instance = classifier }
}
