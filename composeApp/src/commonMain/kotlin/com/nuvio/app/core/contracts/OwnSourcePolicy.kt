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

    internal fun resetForTest() {
        contentIdPredicates.resetForTest()
        providerIdPredicates.resetForTest()
    }
}
