package com.nuvio.app.features.home

import kotlinx.serialization.Serializable

/** Stable ids of the Home sections, shared with every Tuvora app through the synced `home_sections` key. */
object HomeSectionIds {
    const val HERO = "hero"
    const val CONTINUE_WATCHING = "continue_watching"
    const val LIVE_RECENTS = "live_recents"
    const val NEW_EPISODES = "new_episodes"
    const val SPORTS = "sports"
    const val UPCOMING = "upcoming"

    /** Anchor where the ordered catalog / collection rows render; their own order stays in the catalog items. */
    const val CATALOGS = "catalogs"
}

@Serializable
data class HomeSectionEntry(
    val id: String,
    val enabled: Boolean = true,
)

/**
 * The user's Home section layout. [customized] false (or no config at all) means "use the device
 * default"; the stored [sections] are then ignored.
 */
@Serializable
data class HomeSectionsConfig(
    val customized: Boolean = false,
    val sections: List<HomeSectionEntry> = emptyList(),
)

/**
 * Pure decisions about the order and visibility of the Home sections. No I/O: the Home screen, the
 * layout settings and the sync payload all go through here, so every app resolves a layout the same way.
 *
 * - No config, or `customized = false`: today's order, except that a profile with watch history
 *   (any Continue Watching item or recent live channel) gets Continue Watching and the Live TV row first.
 * - Customized: the stored list is the truth. Ids this build does not know, or [supported] excludes,
 *   keep their position in what is written back and are skipped on render. Supported ids missing from
 *   the list (a newer or older device wrote it) are appended in default order, enabled.
 */
object HomeSectionOrderPolicy {
    /** Today's order, hero first. */
    val DEFAULT_ORDER: List<String> = listOf(
        HomeSectionIds.HERO,
        HomeSectionIds.NEW_EPISODES,
        HomeSectionIds.SPORTS,
        HomeSectionIds.CONTINUE_WATCHING,
        HomeSectionIds.LIVE_RECENTS,
        HomeSectionIds.UPCOMING,
        HomeSectionIds.CATALOGS,
    )

    private val HISTORY_FIRST = listOf(HomeSectionIds.CONTINUE_WATCHING, HomeSectionIds.LIVE_RECENTS)

    fun defaultOrder(hasWatchHistory: Boolean): List<String> =
        if (hasWatchHistory) HISTORY_FIRST + (DEFAULT_ORDER - HISTORY_FIRST.toSet()) else DEFAULT_ORDER

    /** The full resolved list, unknown ids included (they are kept so a write-back does not lose them). */
    fun resolve(config: HomeSectionsConfig?, hasWatchHistory: Boolean): List<HomeSectionEntry> {
        if (config == null || !config.customized) {
            return defaultOrder(hasWatchHistory).map { HomeSectionEntry(it, enabled = true) }
        }
        val seen = mutableSetOf<String>()
        val stored = config.sections.filter { seen.add(it.id) }
        val missing = DEFAULT_ORDER.filter { it !in seen }.map { HomeSectionEntry(it, enabled = true) }
        return stored + missing
    }

    /** The sections the settings list shows: [resolve] without ids this build cannot render. */
    fun listed(
        config: HomeSectionsConfig?,
        hasWatchHistory: Boolean,
        supported: Set<String> = DEFAULT_ORDER.toSet(),
    ): List<HomeSectionEntry> = resolve(config, hasWatchHistory).filter { it.id in supported }

    /** Ids to render, in order: enabled and supported only. */
    fun visibleOrder(
        config: HomeSectionsConfig?,
        hasWatchHistory: Boolean,
        supported: Set<String> = DEFAULT_ORDER.toSet(),
    ): List<String> = listed(config, hasWatchHistory, supported).filter { it.enabled }.map { it.id }

    /** Moves [fromId] to where [toId] is, in the full list (unknown ids keep their place). A user change: customized. A no-op returns [config] untouched. */
    fun move(
        config: HomeSectionsConfig?,
        hasWatchHistory: Boolean,
        fromId: String,
        toId: String,
    ): HomeSectionsConfig {
        val list = resolve(config, hasWatchHistory).toMutableList()
        val from = list.indexOfFirst { it.id == fromId }
        val to = list.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || from == to) return config ?: HomeSectionsConfig()
        list.add(to, list.removeAt(from))
        return HomeSectionsConfig(customized = true, sections = list)
    }

    /** Shows or hides one section. A user change: customized. */
    fun setEnabled(
        config: HomeSectionsConfig?,
        hasWatchHistory: Boolean,
        id: String,
        enabled: Boolean,
    ): HomeSectionsConfig {
        val list = resolve(config, hasWatchHistory).map { if (it.id == id) it.copy(enabled = enabled) else it }
        return HomeSectionsConfig(customized = true, sections = list)
    }

    /** "Reset to Default": back to the device default, written explicitly so other devices follow. */
    fun reset(): HomeSectionsConfig = HomeSectionsConfig(
        customized = false,
        sections = DEFAULT_ORDER.map { HomeSectionEntry(it, enabled = true) },
    )

    /** Whether setting [id] to [enabled] would change anything, so a no-op toggle never marks the layout customized. */
    fun wouldChange(config: HomeSectionsConfig?, hasWatchHistory: Boolean, id: String, enabled: Boolean): Boolean {
        if (config == null || !config.customized) return !enabled
        val entry = resolve(config, hasWatchHistory).firstOrNull { it.id == id } ?: return false
        return entry.enabled != enabled
    }

    /** The listed entries with the three sections that have their own on/off switch taken from those switches. */
    fun withToggles(
        entries: List<HomeSectionEntry>,
        heroEnabled: Boolean,
        continueWatchingVisible: Boolean,
        showLiveOnHome: Boolean,
    ): List<HomeSectionEntry> = entries.map { entry ->
        when (entry.id) {
            HomeSectionIds.HERO -> entry.copy(enabled = heroEnabled)
            HomeSectionIds.CONTINUE_WATCHING -> entry.copy(enabled = continueWatchingVisible)
            HomeSectionIds.LIVE_RECENTS -> entry.copy(enabled = showLiveOnHome)
            else -> entry
        }
    }

    /**
     * The history answer to order Home by. Any current history wins; "none" is only believed once the
     * history has settled, until then the last stored answer holds so a returning user's Home does not
     * reorder on every cold launch.
     */
    fun effectiveHistory(stored: Boolean, current: Boolean, settled: Boolean): Boolean =
        if (current) true else if (settled) false else stored

    /**
     * Whether the Live TV recents row is gated by the Continue Watching switch. Only an untouched layout
     * keeps that coupling; a customised one shows each by its own flag.
     */
    fun liveRecentsFollowContinueWatching(config: HomeSectionsConfig?): Boolean = config?.customized != true

    /** True when there is nothing for the Continue Watching block to show, honouring the coupling above. */
    fun hasNoContinueContent(
        config: HomeSectionsConfig?,
        continueWatchingVisible: Boolean,
        hasContinueWatchingRows: Boolean,
        hasLiveRecents: Boolean,
    ): Boolean = if (liveRecentsFollowContinueWatching(config)) {
        !continueWatchingVisible || (!hasContinueWatchingRows && !hasLiveRecents)
    } else {
        (!continueWatchingVisible || !hasContinueWatchingRows) && !hasLiveRecents
    }

    /** Remote layout -> the switches that mirror it. Null for a section not in the layout. */
    fun toggleFor(config: HomeSectionsConfig?, id: String): Boolean? =
        config?.takeIf { it.customized }?.sections?.firstOrNull { it.id == id }?.enabled
}
