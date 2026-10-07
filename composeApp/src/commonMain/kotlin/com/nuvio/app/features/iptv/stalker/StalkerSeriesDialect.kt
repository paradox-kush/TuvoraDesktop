package com.nuvio.app.features.iptv.stalker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which series model a Stalker portal speaks (B02). KMP twin of NuvioTV's StalkerSeriesDialect.
 *
 *  - [Dialect.XC] — Xtream-Codes / XUI / XC_VM emulators: a `type=series` module; `movie_id=<series>`
 *    returns season rows whose `cmd` plays with create_link + `series=<episode>`.
 *  - [Dialect.MINISTRA] — genuine Ministra (stock `server/lib/vod.class.php`): there is NO series
 *    module. A series is a `type=vod` row with `is_series=1`; `get_ordered_list` walks it as
 *    `movie_id` -> seasons (`season_number`), `+season_id` -> episodes (`series_number`, paged 14 a
 *    page), `+episode_id` -> files, and a FILE row's `cmd` (`/media/file_<id>.mpg`) is what
 *    create_link plays.
 *
 * Decided by what the portal ANSWERED, never by its URL — resellers alias `/stalker_portal/`, and
 * iptvnator shipped three bugs from URL-shape rules (#850/#686/#755). Pure: the client feeds it.
 */
object StalkerSeriesDialect {

    enum class Dialect { XC, MINISTRA }

    /** A season or episode node: the portal row id the next level asks by, and its visible number. */
    data class Node(val id: Int, val number: Int)

    /**
     * XC when `type=series` answered with categories; MINISTRA when it didn't but `type=vod` did;
     * null when neither answered (nothing is proven — the caller surfaces the original failure).
     */
    fun allowsVodFallback(probeFailed: Boolean, emptySection: Boolean): Boolean = !probeFailed || emptySection

    fun decide(seriesCategoriesUsable: Boolean, vodCategoriesUsable: Boolean): Dialect? = when {
        seriesCategoriesUsable -> Dialect.XC
        vodCategoriesUsable -> Dialect.MINISTRA
        else -> null
    }

    /** Legacy MAG rows have episode numbers in `series`, often with is_series=0 or absent. */
    fun isSeriesRow(row: JsonObject): Boolean =
        row.strField("is_series")?.let { it.equals("true", true) || (it.toIntOrNull() ?: 0) != 0 } == true ||
            legacyEpisodeNumbers(row).isNotEmpty()

    fun legacyEpisodeNumbers(row: JsonObject): List<Int> {
        val raw = row["series"]
        val array = raw as? JsonArray ?: (raw as? JsonPrimitive)?.contentOrNull?.let {
            runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull()
        }
        return array.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
            .filter { it > 0 }.distinct().sorted()
    }

    fun movieFilesParams(movieId: Int): Map<String, String> = tree("movie_id" to movieId.toString(), page = 1)

    fun seasonsParams(seriesId: Int, page: Int): Map<String, String> =
        tree("movie_id" to seriesId.toString(), page = page)

    fun episodesParams(seriesId: Int, seasonId: Int, page: Int): Map<String, String> =
        tree("movie_id" to seriesId.toString(), "season_id" to seasonId.toString(), page = page)

    fun filesParams(seriesId: Int, seasonId: Int, episodeId: Int): Map<String, String> =
        tree("movie_id" to seriesId.toString(), "season_id" to seasonId.toString(), "episode_id" to episodeId.toString(), page = 1)

    /** A season row: id + `season_number` (else the number in "Season N"). */
    fun season(row: JsonObject): Node? {
        val id = row.intField("id") ?: return null
        val n = row.intField("season_number") ?: SEASON_NAME.find(row.strField("name").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return Node(id, n)
    }

    /** An episode row: id + `series_number` (else the number in "Episode N"). */
    fun episode(row: JsonObject): Node? {
        val id = row.intField("id") ?: return null
        val n = row.intField("series_number") ?: EPISODE_NAME.find(row.strField("name").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return Node(id, n)
    }

    private fun tree(vararg ids: Pair<String, String>, page: Int): Map<String, String> = buildMap {
        put("type", "vod")
        put("action", "get_ordered_list")
        ids.forEach { (k, v) -> put(k, v) }
        put("p", page.toString())
    }

    private fun JsonObject.strField(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intField(key: String): Int? = strField(key)?.trim()?.toIntOrNull()

    private val SEASON_NAME = Regex("""(?i)season\s*(\d+)""")
    private val EPISODE_NAME = Regex("""(?i)episode\s*(\d+)""")
}
