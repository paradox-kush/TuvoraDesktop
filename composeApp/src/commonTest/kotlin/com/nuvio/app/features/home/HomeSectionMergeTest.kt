package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Wave 3 / P0: contributed rows join Home through the same preferences as add-on rows. */
class HomeSectionMergeTest {

    private fun section(key: String, vararg ids: String, title: String = key) = HomeCatalogSection(
        key = key,
        title = title,
        subtitle = "",
        addonName = "",
        target = CatalogTarget.Source("src", key, "movie"),
        items = ids.map { MetaPreview(id = it, type = "movie", name = it) },
    )

    private fun pref(order: Int, enabled: Boolean = true, hero: Boolean = true, title: String = "") =
        HomeCatalogPreference(customTitle = title, enabled = enabled, heroSourceEnabled = hero, order = order)

    private val addonRows = listOf(section("addon:a", "m1"), section("addon:b", "m2"))
    private val addonPrefs = mapOf("addon:a" to pref(0), "addon:b" to pref(1))

    @Test
    fun `nothing contributed returns the add-on rows untouched`() {
        assertSame(addonRows, HomeSectionMerge.merge(addonRows, emptyList(), addonPrefs))
    }

    @Test
    fun `only empty or hidden contributed rows also leave the add-on rows untouched`() {
        val contributed = listOf(section("ms:srv:latest"), section("ms:srv:nextup", "e1"))
        val prefs = addonPrefs + ("ms:srv:nextup" to pref(5, enabled = false))
        assertSame(addonRows, HomeSectionMerge.merge(addonRows, contributed, prefs))
    }

    @Test
    fun `an unordered contributed row goes after the ordered rows in registration order`() {
        val contributed = listOf(section("ms:srv:latest", "s1"), section("ms:srv:nextup", "s2"))
        val merged = HomeSectionMerge.merge(addonRows, contributed, addonPrefs)
        assertEquals(listOf("addon:a", "addon:b", "ms:srv:latest", "ms:srv:nextup"), merged.map { it.key })
    }

    @Test
    fun `a contributed row follows its stored order between add-on rows`() {
        val contributed = listOf(section("ms:srv:latest", "s1"))
        val prefs = mapOf("addon:a" to pref(0), "addon:b" to pref(2), "ms:srv:latest" to pref(1))
        assertEquals(
            listOf("addon:a", "ms:srv:latest", "addon:b"),
            HomeSectionMerge.merge(addonRows, contributed, prefs).map { it.key },
        )
    }

    @Test
    fun `a hidden contributed row is dropped and a custom title is applied`() {
        val contributed = listOf(section("ms:srv:latest", "s1"), section("ms:srv:nextup", "s2", title = "Next Up"))
        val prefs = addonPrefs + ("ms:srv:latest" to pref(3, enabled = false)) + ("ms:srv:nextup" to pref(4, title = "Up next at home"))
        val merged = HomeSectionMerge.merge(addonRows, contributed, prefs)
        assertEquals(listOf("addon:a", "addon:b", "ms:srv:nextup"), merged.map { it.key })
        assertEquals("Up next at home", merged.last().title)
    }

    @Test
    fun `a contributed key that collides with an add-on row loses`() {
        val contributed = listOf(section("addon:a", "intruder"))
        assertSame(addonRows, HomeSectionMerge.merge(addonRows, contributed, addonPrefs))
    }

    @Test
    fun `contributed rows reach the hero only when the viewer opted them in`() {
        val contributed = listOf(section("ms:srv:latest", "s1"), section("ms:srv:nextup", "s2"), section("ms:srv:cw", "s3"))
        val prefs = mapOf(
            "ms:srv:latest" to pref(1, hero = true),
            "ms:srv:nextup" to pref(2, hero = false),
            // ms:srv:cw has no stored preference at all
        )
        assertEquals(listOf("ms:srv:latest"), HomeSectionMerge.heroEligible(contributed, prefs).map { it.key })
    }

    @Test
    fun `a hidden row cannot feed the hero`() {
        val contributed = listOf(section("ms:srv:latest", "s1"))
        val prefs = mapOf("ms:srv:latest" to pref(1, enabled = false, hero = true))
        assertEquals(emptyList(), HomeSectionMerge.heroEligible(contributed, prefs))
    }
}
