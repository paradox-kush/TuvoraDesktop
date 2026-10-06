package com.nuvio.app.core.contracts

/**
 * Which content ids and stream-provider ids belong to a user-owned SOURCE (an IPTV playlist, a media
 * server) rather than to an add-on/debrid provider. Shared code asks this instead of hard-coding the
 * IPTV literals ("xtream:" content ids, "xtream" / "xtream-match:" provider ids):
 *  - [isOwnContentId]: the id is played by its own source lane, never needs an add-on or scraper
 *    (`PlaybackAvailability`);
 *  - [isOwnProviderId]: the stream came from an own source, so its link is never cached
 *    (`StreamLinkCacheRepository`), is usable in store builds where add-ons are discovery-only
 *    (`AddonSourcePolicy`) and, with the stream-source port, qualifies for credential refresh.
 *
 * Two predicate registries, never one - a content id and a provider id are different namespaces.
 * Features register their predicates from the composition root (no fork reference here: same shape as
 * [IptvContentClassifierAccess]). A duplicate name is refused.
 */
object OwnSourcePolicy {
    private val contentIdPredicates = NamedRegistry<(String) -> Boolean>("own content-id predicate")
    private val providerIdPredicates = NamedRegistry<(String) -> Boolean>("own provider-id predicate")

    fun registerContentIdPredicate(name: String, predicate: (String) -> Boolean) =
        contentIdPredicates.register(name, predicate)

    fun registerProviderIdPredicate(name: String, predicate: (String) -> Boolean) =
        providerIdPredicates.register(name, predicate)

    /** True when [id] is a namespaced content id of any registered own source. */
    fun isOwnContentId(id: String?): Boolean =
        id != null && contentIdPredicates.all.any { it(id) }

    /** True when [addonId] (a stream group / provider id) belongs to any registered own source. */
    fun isOwnProviderId(addonId: String?): Boolean =
        !addonId.isNullOrBlank() && providerIdPredicates.all.any { it(addonId) }

    private val scrobbleExclusions = NamedRegistry<(String) -> Boolean>("tracking-scrobble exclusion")
    private val telemetryRewriters = NamedRegistry<(id: String, salt: String) -> String?>("telemetry-id rewriter")

    /**
     * A source whose items must never be scrobbled to a tracking provider (Trakt/Simkl/MDBList) - a media server's
     * own items in v1 (owner decision 2026-10-06; servers usually run their own tracker plugin). A TMDB title merely
     * PLAYED from such a source is a different content id and is unaffected.
     */
    fun registerScrobbleExclusion(name: String, predicate: (String) -> Boolean) =
        scrobbleExclusions.register(name, predicate)

    fun isExcludedFromTrackingScrobble(id: String?): Boolean =
        id != null && scrobbleExclusions.all.any { it(id) }

    /**
     * A source whose content ids embed something that must not leave the device (a media server's machine id and user
     * id) registers how a TELEMETRY event may name the item instead: null = "not mine". Applied at the chokepoints that
     * ship ids off-device (the recommendation event log).
     */
    fun registerTelemetryRewriter(name: String, rewriter: (id: String, salt: String) -> String?) =
        telemetryRewriters.register(name, rewriter)

    /** The id a telemetry event may carry for [id]: the owning source's rewrite, else [id] unchanged. */
    fun telemetryId(id: String, installSalt: String): String =
        telemetryRewriters.all.firstNotNullOfOrNull { it(id, installSalt) } ?: id

    internal fun resetForTest() {
        contentIdPredicates.resetForTest()
        providerIdPredicates.resetForTest()
        scrobbleExclusions.resetForTest()
        telemetryRewriters.resetForTest()
    }
}
