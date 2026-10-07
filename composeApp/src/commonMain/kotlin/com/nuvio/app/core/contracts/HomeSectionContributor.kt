package com.nuvio.app.core.contracts

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import kotlinx.coroutines.CancellationException

/**
 * A row a contributor WOULD show (its settings say it is on), independent of whether it currently has items -
 * what the Home layout settings list so the viewer can reorder / hide / rename it even while it is empty or the
 * server is offline. [key] is the same stable [HomeCatalogSection.key] the row renders under.
 */
data class ContributedRowDeclaration(val key: String, val title: String, val subtitle: String)

/**
 * A source that contributes its own rows to Home (a media server's Continue Watching / Next Up /
 * Recently added). Unlike the fixed-position slots ([HomeSportsSection], [HomeAnnouncementsSection]) the
 * rows are ordinary [HomeCatalogSection]s: [HomeRepository][com.nuvio.app.features.home.HomeRepository]
 * merges them into the published list, so they are orderable and hideable through the same
 * preferences as add-on rows (keyed by [HomeCatalogSection.key]) and can feed the hero.
 *
 * Contract:
 *  - Section keys MUST be stable across re-login: key on the source identity (`{type}:{machineId}` +
 *    list id), never on a user id, or a re-login would orphan the viewer's order/hide choices.
 *  - [sections] is called under Home's lifecycle (refresh), so it may do network work but must be
 *    TTL-gated by the contributor itself - Home does not poll on a timer. Return an empty list when
 *    the source is off, offline or signed out; never throw for expected states.
 *  - "See all" on a contributed row opens a [CatalogTarget.Source]; [ownsSource] / [loadSourcePage]
 *    serve it.
 * With nothing registered Home is unchanged.
 */
interface HomeSectionContributor {
    val name: String

    suspend fun sections(forceRefresh: Boolean): List<HomeCatalogSection>

    /** True when [sourceKey] (a [CatalogTarget.Source.sourceKey]) belongs to this contributor. */
    fun ownsSource(sourceKey: String): Boolean

    /** One page of a "see all" listing for a [CatalogTarget.Source] this contributor owns. */
    suspend fun loadSourcePage(target: CatalogTarget.Source, skip: Int?): CatalogPage

    /**
     * The rows this contributor is configured to show, for the Home layout settings. Cheap and synchronous (no
     * network): derived from the contributor's own settings. Default none.
     */
    fun declaredRows(): List<ContributedRowDeclaration> = emptyList()
}

object HomeSectionContributorRegistry {
    private val contributors = NamedRegistry<HomeSectionContributor>("HomeSectionContributor")

    fun register(contributor: HomeSectionContributor) = contributors.register(contributor.name, contributor)

    val all: List<HomeSectionContributor> get() = contributors.all

    val isEmpty: Boolean get() = contributors.isEmpty

    /**
     * Every contributor's rows in registration order. A failing contributor contributes nothing (it
     * must not blank Home); a repeated section key keeps the first row (keys are the join with Home
     * preferences, so they must be unique).
     */
    suspend fun collectSections(forceRefresh: Boolean): List<HomeCatalogSection> {
        val seen = mutableSetOf<String>()
        return all.flatMap { contributor ->
            try {
                contributor.sections(forceRefresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
        }.filter { seen.add(it.key) }
    }

    /** Every contributor's declared rows (a failing contributor declares none), first declaration of a key wins. */
    fun declaredRows(): List<ContributedRowDeclaration> {
        val seen = mutableSetOf<String>()
        return all.flatMap { contributor ->
            try {
                contributor.declaredRows()
            } catch (_: Throwable) {
                emptyList()
            }
        }.filter { seen.add(it.key) }
    }

    /** The "see all" page for [target], or null when no contributor owns it. */
    suspend fun loadSourcePage(target: CatalogTarget.Source, skip: Int?): CatalogPage? =
        all.firstOrNull { it.ownsSource(target.sourceKey) }?.loadSourcePage(target, skip)

    internal fun resetForTest() = contributors.resetForTest()
}
