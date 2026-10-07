package com.nuvio.app.features.catalog

import com.nuvio.app.core.contracts.HomeSectionContributor
import com.nuvio.app.core.contracts.HomeSectionContributorRegistry
import com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Wave 3 / P0: "see all" on a contributed Home row opens a [CatalogTarget.Source] served by its owner. */
class CatalogSourceTargetTest {

    private val requested = mutableListOf<Triple<String, String, Int?>>()

    private val contributor = object : HomeSectionContributor {
        override val name = "server"
        override suspend fun sections(forceRefresh: Boolean): List<HomeCatalogSection> = emptyList()
        override fun ownsSource(sourceKey: String) = sourceKey == "jellyfin:machine1"
        override suspend fun loadSourcePage(target: CatalogTarget.Source, skip: Int?): CatalogPage {
            requested += Triple(target.sourceKey, target.listId, skip)
            return CatalogPage(
                items = listOf(MetaPreview(id = "ms:jellyfin:machine1:u:movie:7", type = "movie", name = "Heat")),
                rawItemCount = 1,
                nextSkip = null,
            )
        }
    }

    @BeforeTest
    fun setUp() {
        resetAllSourceRegistriesForTest()
        HomeSectionContributorRegistry.register(contributor)
        CatalogRepository.clear()
    }

    @AfterTest
    fun tearDown() {
        CatalogRepository.clear()
        resetAllSourceRegistriesForTest()
    }

    @Test
    fun `a source target loads its first page through the owning contributor`() = runBlocking {
        CatalogRepository.load(CatalogTarget.Source(sourceKey = "jellyfin:machine1", listId = "latest", contentType = "movie"))

        val state = withTimeout(10_000) { CatalogRepository.uiState.first { !it.isLoading && it.items.isNotEmpty() } }

        assertEquals(listOf("ms:jellyfin:machine1:u:movie:7"), state.items.map { it.id })
        assertEquals(
            listOf(Triple<String, String, Int?>("jellyfin:machine1", "latest", null)),
            requested.toList(),
            "first page asks with no skip",
        )
    }
}
