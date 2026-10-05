package com.nuvio.app.features.iptv.stalker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B02: which series dialect a portal speaks, decided by what it answered. Twin of NuvioTV's test. */
class StalkerSeriesDialectTest {

    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun aPortalThatAnswersTypeSeriesIsXcFamily() {
        assertEquals(StalkerSeriesDialect.Dialect.XC, StalkerSeriesDialect.decide(true, true), "series answered")
    }

    @Test
    fun noSeriesModuleButWorkingVodIsGenuineMinistra() {
        assertEquals(StalkerSeriesDialect.Dialect.MINISTRA, StalkerSeriesDialect.decide(false, true), "vod answered")
    }

    @Test
    fun neitherAnsweringProvesNothing() {
        assertNull(StalkerSeriesDialect.decide(false, false), "undecided")
    }

    @Test
    fun isSeriesIsReadLeniently() {
        assertTrue(StalkerSeriesDialect.isSeriesRow(obj("""{"is_series":"1"}""")), "string 1")
        assertTrue(StalkerSeriesDialect.isSeriesRow(obj("""{"is_series":1}""")), "int 1")
        assertTrue(StalkerSeriesDialect.isSeriesRow(obj("""{"is_series":true}""")), "bool")
        assertFalse(StalkerSeriesDialect.isSeriesRow(obj("""{"is_series":"0"}""")), "string 0")
        assertFalse(StalkerSeriesDialect.isSeriesRow(obj("""{"id":"1"}""")), "absent (XC rows)")
    }

    @Test
    fun treeParamsWalkMovieIdThenSeasonIdThenEpisodeIdUnderTypeVod() {
        assertEquals(mapOf("type" to "vod", "action" to "get_ordered_list", "movie_id" to "500", "p" to "1"),
            StalkerSeriesDialect.seasonsParams(500, 1))
        assertEquals(mapOf("type" to "vod", "action" to "get_ordered_list", "movie_id" to "500", "season_id" to "5002", "p" to "2"),
            StalkerSeriesDialect.episodesParams(500, 5002, 2))
        assertEquals(mapOf("type" to "vod", "action" to "get_ordered_list", "movie_id" to "500", "season_id" to "5002",
            "episode_id" to "500203", "p" to "1"),
            StalkerSeriesDialect.filesParams(500, 5002, 500203))
    }

    @Test
    fun seasonAndEpisodeNumbersComeFromTheirOwnFieldsNamesAsFallback() {
        assertEquals(StalkerSeriesDialect.Node(5002, 2), StalkerSeriesDialect.season(obj("""{"id":"5002","season_number":"2","name":"Season 2"}""")))
        assertEquals(StalkerSeriesDialect.Node(77, 4), StalkerSeriesDialect.season(obj("""{"id":"77","name":"Season 4. Finale"}""")))
        assertEquals(StalkerSeriesDialect.Node(500203, 3), StalkerSeriesDialect.episode(obj("""{"id":"500203","series_number":"3"}""")))
        assertNull(StalkerSeriesDialect.episode(obj("""{"series_number":"3"}""")), "no id")
    }
}
