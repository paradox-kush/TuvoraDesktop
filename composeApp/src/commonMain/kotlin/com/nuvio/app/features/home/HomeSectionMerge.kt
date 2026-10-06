package com.nuvio.app.features.home

/**
 * Joins the rows [com.nuvio.app.core.contracts.HomeSectionContributor]s supply (a media server's own
 * lists) with the add-on catalog rows, through the SAME per-key preferences: orderable, hideable,
 * renamable (never the hero in v1). Pure so it tests without Home's repositories.
 *
 * With nothing contributed the add-on rows come back untouched (same list), which is what keeps Home
 * identical while no source contributes.
 */
internal object HomeSectionMerge {

    /**
     * [sections] are the add-on rows, already ordered, filtered and titled. A contributed row is shown
     * unless its preference hides it or it is empty; it is ordered by its preference (a row with no
     * stored order goes after every ordered one, registration order kept) and takes the viewer's custom
     * title. A contributed key that collides with an add-on row loses - the add-on row keeps the key.
     */
    fun merge(
        sections: List<HomeCatalogSection>,
        contributed: List<HomeCatalogSection>,
        preferences: Map<String, HomeCatalogPreference>,
    ): List<HomeCatalogSection> {
        if (contributed.isEmpty()) return sections
        val taken = sections.mapTo(mutableSetOf(), HomeCatalogSection::key)
        val visible = contributed
            .filter { it.items.isNotEmpty() && preferences[it.key]?.enabled != false && taken.add(it.key) }
            .map { section ->
                val customTitle = preferences[section.key]?.customTitle.orEmpty()
                if (customTitle.isBlank()) section else section.copy(title = customTitle)
            }
        if (visible.isEmpty()) return sections
        return (sections + visible).sortedBy { preferences[it.key]?.order ?: Int.MAX_VALUE }
    }

    /**
     * Contributed rows that may feed the hero: NONE in v1 (owner decision 2026-10-06 - a signed-in server's library
     * must not take over the home banner). The Home layout UI already cannot opt a contributed row in; this makes
     * the rule hold even for a stale or synced `heroSourceEnabled` on a contributed key.
     */
    @Suppress("UNUSED_PARAMETER")
    fun heroEligible(
        contributed: List<HomeCatalogSection>,
        preferences: Map<String, HomeCatalogPreference>,
    ): List<HomeCatalogSection> = emptyList()
}
