package com.nuvio.app.features.iptv.stalker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * B02: stock Ministra reads a non-`*` `genre` on vod lists as a GENRE filter, so sending the
 * category id there emptied every Movies row. Category goes in `category` for vod/series; `itv`
 * keeps filtering by `genre`.
 */
class StalkerListParamsTest {

    @Test
    fun `a vod category goes in category with no genre filter`() {
        val p = StalkerListParams.forPage("vod", "12", null, 1)
        assertEquals("12", p["category"])
        assertEquals("*", p["genre"])
        assertEquals("get_ordered_list", p["action"])
        assertEquals("1", p["p"])
    }

    @Test
    fun `a series category goes in category with no genre filter`() {
        val p = StalkerListParams.forPage("series", "7", null, 3)
        assertEquals("7", p["category"])
        assertEquals("*", p["genre"])
        assertEquals("3", p["p"])
    }

    @Test
    fun `live keeps filtering by genre and sends no category`() {
        val p = StalkerListParams.forPage("itv", "5", null, 2)
        assertEquals("5", p["genre"])
        assertFalse("category" in p)
    }

    @Test
    fun `no category means all in every section`() {
        assertEquals("*", StalkerListParams.forPage("vod", null, null, 1)["category"])
        assertEquals("*", StalkerListParams.forPage("itv", null, null, 1)["genre"])
    }

    @Test
    fun `search rides along unchanged`() {
        assertEquals("arrival", StalkerListParams.forPage("vod", null, "arrival", 1)["search"])
        assertFalse("search" in StalkerListParams.forPage("vod", null, null, 1))
    }
}
