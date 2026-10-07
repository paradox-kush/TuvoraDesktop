package com.nuvio.app.core.contracts

/**
 * Neutral content-classification port (seams S5-E / S7 prep). Lets shared code (WatchProgress rules,
 * stream-cache grouping) classify content without naming features/iptv. Pure reads, no iptv types.
 * Sources are PLURAL: each registers in [ContentClassifierRegistry]; shared code reads the combined view
 * from [IptvContentClassifierAccess.classifier].
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

/** Every registered classifier, in registration order; a duplicate name is refused. */
object ContentClassifierRegistry {
    private val classifiers = NamedRegistry<IptvContentClassifier>("IptvContentClassifier")

    fun register(name: String, classifier: IptvContentClassifier) = classifiers.register(name, classifier)

    val all: List<IptvContentClassifier> get() = classifiers.all

    internal fun resetForTest() = classifiers.resetForTest()
}

/**
 * Composite classifier: an id is live / orphaned / Xtream / a stream group if ANY source says so (a
 * source answers false for ids it does not own), and a poster is the first one found. Nothing
 * registered -> nothing is own-source content (tests, previews, IPTV-free builds).
 */
class CompositeContentClassifier(
    private val classifiers: () -> List<IptvContentClassifier>,
) : IptvContentClassifier {
    override fun isLiveId(id: String) = classifiers().any { it.isLiveId(id) }
    override fun isOrphaned(id: String) = classifiers().any { it.isOrphaned(id) }
    override fun isXtreamId(id: String) = classifiers().any { it.isXtreamId(id) }
    override fun posterFor(id: String): String? = classifiers().firstNotNullOfOrNull { it.posterFor(id) }
    override fun isXtreamStreamGroup(addonId: String) = classifiers().any { it.isXtreamStreamGroup(addonId) }
}

/** Thin read facade (call sites do not churn): the combined view of [ContentClassifierRegistry]. */
object IptvContentClassifierAccess {
    private val composite = CompositeContentClassifier { ContentClassifierRegistry.all }

    val classifier: IptvContentClassifier get() = composite

    fun resetForTest() = ContentClassifierRegistry.resetForTest()
}
