package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpStreamLines
import com.nuvio.app.features.iptv.match.IndexedItem
import com.nuvio.app.features.iptv.match.TitleNormalizer
import com.nuvio.app.features.iptv.match.XtreamCatalogIndexParser
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.util.decodeBase64String
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Talks to one Xtream panel: builds `player_api.php` + stream URLs, fetches via the
 * shared [httpGetText], maps DTOs -> domain models. KMP twin of NuvioTV's XtreamClient.
 *
 * ponytail: stream URLs reuse the account's entered baseUrl; short_epg gives now+next
 * cheaply. Full XMLTV grid and a per-host server_info override are the upgrade paths.
 */
object XtreamClient : IptvClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** Verifies credentials. Success only when the panel reports auth=1 and an active status. */
    override suspend fun verify(acc: XtreamAccount): Result<Unit> = call {
        val info = userInfo(playerApi(acc), acc.dnsProvider)
        check(info?.get("auth").asIntOrNull() == 1) { "Authentication failed" }
        val status = info?.get("status").asStringOrNull()?.lowercase() ?: ""
        check(status.isEmpty() || status == "active") { "Account status: ${info?.get("status").asStringOrNull()}" }
    }

    /** Live account status: active/expired, trial flag, expiry, and current vs max connections. */
    override suspend fun accountInfo(acc: XtreamAccount): Result<XtreamAccountInfo?> = call {
        val info = userInfo(playerApi(acc), acc.dnsProvider) ?: return@call null
        XtreamAccountInfo(
            status = info["status"].asStringOrNull(),
            isTrial = info["is_trial"].asStringOrNull() == "1",
            expiresAtEpochSec = info["exp_date"].asStringOrNull()?.toLongOrNull(),
            maxConnections = info["max_connections"].asIntOrNull(),
            activeConnections = info["active_cons"].asIntOrNull()
        )
    }

    /** `user_info` object from the no-action player_api call, parsed loosely. */
    private suspend fun userInfo(url: String, dnsProvider: String?): JsonObject? =
        runCatching { json.parseToJsonElement(panelGetText(url, dnsProvider)).jsonObject["user_info"] as? JsonObject }.getOrNull()

    override suspend fun liveCategories(acc: XtreamAccount) = categories(acc, "get_live_categories")
    override suspend fun vodCategories(acc: XtreamAccount) = categories(acc, "get_vod_categories")
    override suspend fun seriesCategories(acc: XtreamAccount) = categories(acc, "get_series_categories")

    // Bulk lists are parsed loosely — field by field from raw JSON, not through a strict DTO.
    // Xtream panels are wildly inconsistent about types (a field is an int on one panel, a
    // quoted string on another, a bare number where a string is expected on a third — e.g.
    // onnipsite sends `rating` as `0`, not `"0"`). A strict decode throws on the FIRST such
    // field and loses the ENTIRE catalog, so the provider's index silently never builds.
    override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = call {
        streamArray(acc, playerApi(acc, "get_live_streams", categoryId)) { o ->
            val id = o["stream_id"].asIntOrNull() ?: return@streamArray null
            XtreamChannel(
                streamId = id,
                name = o["name"].asStringOrNull() ?: "",
                logo = o["stream_icon"].asStringOrNull(),
                epgChannelId = o["epg_channel_id"].asStringOrNull(),
                categoryId = o["category_id"].asStringOrNull(),
                hasArchive = (o["tv_archive"].asIntOrNull() ?: 0) > 0,
                streamUrl = streamUrl(acc, "live", id, "ts")
            )
        }
    }

    override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> = call {
        streamArray(acc, playerApi(acc, "get_vod_streams", categoryId)) { o -> parseVodItem(acc, o) }
    }

    override suspend fun series(acc: XtreamAccount, categoryId: String?): Result<List<XtreamSeriesItem>> = call {
        streamArray(acc, playerApi(acc, "get_series", categoryId)) { o -> parseSeriesItem(o) }
    }

    /**
     * Catalog reduced to match-index rows.
     *
     * [vodMovies]/[series] stay as they are for the browse screens, which need the full model.
     * The index only stores six fields, so building the full model first cost an extra
     * whole-catalog copy in heap — including a stream URL constructed per item that the index
     * has no field for. That peak is what was getting the app lowmemorykilled on low-RAM
     * devices right after a playlist was added.
     */
    internal suspend fun vodIndexItems(acc: XtreamAccount): Result<List<IndexedItem>> = call {
        streamArray(acc, playerApi(acc, "get_vod_streams")) { o -> parseVodIndexItem(o) }
    }

    /** Series half of [vodIndexItems]. */
    internal suspend fun seriesIndexItems(acc: XtreamAccount): Result<List<IndexedItem>> = call {
        streamArray(acc, playerApi(acc, "get_series")) { o -> parseSeriesIndexItem(o) }
    }

    /**
     * [vodIndexItems], streamed: each parsed row goes straight to [onItem] and is then garbage —
     * the full catalog never exists in heap as one list. This is the index build's path: the list
     * variant peaked at the whole catalog (~40-50 MB of IndexedItem for a 175k panel) on exactly
     * the devices whose heap can't take it. Returns the delivered-row count; throws (like the list
     * variant) on a truncated body, so a partial catalog can't finalize a sync.
     */
    internal suspend fun vodIndexItemsInto(acc: XtreamAccount, onItem: (IndexedItem) -> Unit): Result<Int> = call {
        streamArrayInto(acc, playerApi(acc, "get_vod_streams"), { o -> parseVodIndexItem(o) }, onItem)
    }

    /** Series half of [vodIndexItemsInto]. */
    internal suspend fun seriesIndexItemsInto(acc: XtreamAccount, onItem: (IndexedItem) -> Unit): Result<Int> = call {
        streamArrayInto(acc, playerApi(acc, "get_series"), { o -> parseSeriesIndexItem(o) }, onItem)
    }

    /** One VOD list entry -> index row, skipping the domain model entirely. internal for tests. */
    internal fun parseVodIndexItem(o: JsonObject): IndexedItem? {
        val id = o["stream_id"].asIntOrNull() ?: return null
        val name = o["name"].asStringOrNull() ?: ""
        return IndexedItem(
            sid = id,
            name = name,
            year = TitleNormalizer.yearOf(name),
            tmdb = o["tmdb"].asIntOrNull()?.takeIf { it > 0 },
            ext = o["container_extension"].asStringOrNull()?.takeIf { it.isNotBlank() },
            poster = o["stream_icon"].asStringOrNull()?.takeIf { it.isNotBlank() },
            categoryId = o["category_id"].asStringOrNull(),
        )
    }

    /** One live list entry -> index row (P7: the index doubles as the browse catalog). */
    internal fun parseLiveIndexItem(o: JsonObject): IndexedItem? {
        val id = o["stream_id"].asIntOrNull() ?: return null
        return IndexedItem(
            sid = id,
            name = o["name"].asStringOrNull() ?: "",
            year = null,
            tmdb = null,
            ext = null,
            poster = o["stream_icon"].asStringOrNull()?.takeIf { it.isNotBlank() },
            categoryId = o["category_id"].asStringOrNull(),
            epgId = o["epg_channel_id"].asStringOrNull(),
            hasArchive = (o["tv_archive"].asIntOrNull() ?: 0) > 0,
        )
    }

    /** Live half of [vodIndexItemsInto]. */
    internal suspend fun liveIndexItemsInto(acc: XtreamAccount, onItem: (IndexedItem) -> Unit): Result<Int> = call {
        streamArrayInto(acc, playerApi(acc, "get_live_streams"), { o -> parseLiveIndexItem(o) }, onItem)
    }

    /** One series list entry -> index row. internal for tests. */
    internal fun parseSeriesIndexItem(o: JsonObject): IndexedItem? {
        val id = o["series_id"].asIntOrNull() ?: return null
        val name = o["name"].asStringOrNull() ?: ""
        return IndexedItem(
            sid = id,
            name = name,
            year = (o["releaseDate"] ?: o["release_date"]).asStringOrNull()?.trim()?.take(4)?.toIntOrNull()
                ?: TitleNormalizer.yearOf(name),
            tmdb = o["tmdb"].asIntOrNull()?.takeIf { it > 0 },
            ext = null,
            poster = o["cover"].asStringOrNull()?.takeIf { it.isNotBlank() },
            categoryId = o["category_id"].asStringOrNull(),
        )
    }

    override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> = call {
        val url = playerApi(acc, "get_short_epg") + "&stream_id=$streamId&limit=$limit"
        val root = runCatching { json.parseToJsonElement(panelGetText(url, acc.dnsProvider)).jsonObject }.getOrNull() ?: return@call emptyList()
        (root["epg_listings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { o -> parseEpgProgramme(o) }
    }

    /**
     * One EPG row -> domain model, every field read tolerantly (the same rows come back from
     * get_short_epg AND get_simple_data_table; only the latter carries has_archive). internal
     * for tests.
     */
    internal fun parseEpgProgramme(o: JsonObject): XtreamProgram = XtreamProgram(
        title = decodeXtreamBase64(o["title"].asStringOrNull()),
        description = decodeXtreamBase64(o["description"].asStringOrNull()),
        startMs = (o["start_timestamp"].asStringOrNull()?.toLongOrNull() ?: 0L) * 1000,
        endMs = (o["stop_timestamp"].asStringOrNull()?.toLongOrNull() ?: 0L) * 1000,
        nowPlaying = o["now_playing"].asIntOrNull() == 1,
        // Any positive count is a mark; junk or absence stays null — silence, not "no".
        hasArchive = o["has_archive"].asIntOrNull()?.let { it > 0 },
    )

    /**
     * VOD detail for synthetic-meta + TMDB enrichment. Returns null (not a failure) when the
     * panel sends `info: []` — a known quirk — so callers fall back to bare Xtream metadata.
     */
    override suspend fun vodInfo(acc: XtreamAccount, vodId: Int): Result<XtreamVodDetail?> = call {
        val text = panelGetText(playerApi(acc, "get_vod_info") + "&vod_id=$vodId", acc.dnsProvider)
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@call null
        val info = root["info"] as? JsonObject   // null when the panel sends info: []
        val movieData = root["movie_data"] as? JsonObject
        XtreamVodDetail(
            name = movieData?.get("name").asStringOrNull(),
            plot = info?.get("plot").asStringOrNull(),
            genres = info?.get("genre").asStringOrNull()?.splitCsv() ?: emptyList(),
            rating = info?.get("rating").asStringOrNull(),
            releaseDate = (info?.get("releasedate") ?: info?.get("release_date")).asStringOrNull(),
            tmdbId = info?.get("tmdb_id").asIntOrNull(),
            containerExtension = movieData?.get("container_extension").asStringOrNull()
        )
    }

    /**
     * Artwork for one VOD item from get_vod_info — the lazy fallback for panels whose bulk
     * list ships empty stream_icons. null = the panel has no art for it either.
     */
    suspend fun vodArtwork(acc: XtreamAccount, vodId: Int): Result<String?> = call {
        val text = panelGetText(playerApi(acc, "get_vod_info") + "&vod_id=$vodId", acc.dnsProvider)
        val info = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.get("info") as? JsonObject
        info?.get("movie_image").asStringOrNull()?.takeIf { it.isNotBlank() }
            ?: info?.get("cover_big").asStringOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Series half of [vodArtwork] (get_series_info `info.cover`). */
    suspend fun seriesArtwork(acc: XtreamAccount, seriesId: Int): Result<String?> = call {
        val text = panelGetText(playerApi(acc, "get_series_info") + "&series_id=$seriesId", acc.dnsProvider)
        val info = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.get("info") as? JsonObject
        info?.get("cover").asStringOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Series detail incl. flattened episode list. Parsed leniently by hand because panels are
     * wildly inconsistent: an episode's `info` is an object on some episodes and `[]` on others
     * within the SAME series, and season/episode numbers arrive as int or quoted string. A strict
     * decode throws on the first `info: []` and loses every episode — so we walk the JSON instead.
     */
    override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail?> = call {
        val text = panelGetText(playerApi(acc, "get_series_info") + "&series_id=$seriesId", acc.dnsProvider)
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@call null
        val info = root["info"] as? JsonObject
        val episodes = (root["episodes"] as? JsonObject).orEmptyEntries().flatMap { (seasonKey, seasonEps) ->
            (seasonEps as? JsonArray).orEmpty().mapNotNull { element ->
                val e = element as? JsonObject ?: return@mapNotNull null
                val epId = e["id"].asStringOrNull() ?: return@mapNotNull null
                val epInfo = e["info"] as? JsonObject   // null when the panel sends info: []
                val num = e["episode_num"].asIntOrNull() ?: 0
                XtreamEpisode(
                    episodeId = epId,
                    season = e["season"].asIntOrNull() ?: seasonKey.toIntOrNull() ?: 0,
                    episodeNum = num,
                    title = e["title"].asStringOrNull() ?: "Episode $num",
                    plot = epInfo?.get("plot").asStringOrNull(),
                    still = epInfo?.get("movie_image").asStringOrNull(),
                    containerExtension = e["container_extension"].asStringOrNull()
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.episodeNum }))
        XtreamSeriesDetail(
            name = info?.get("name").asStringOrNull(),
            poster = info?.get("cover").asStringOrNull(),
            tmdbId = (info?.get("tmdb_id") ?: info?.get("tmdb")).asIntOrNull(),
            plot = info?.get("plot").asStringOrNull(),
            genres = info?.get("genre").asStringOrNull()?.splitCsv() ?: emptyList(),
            rating = info?.get("rating").asStringOrNull(),
            releaseDate = (info?.get("releaseDate") ?: info?.get("release_date") ?: info?.get("releasedate")).asStringOrNull(),
            episodes = episodes
        )
    }

    // --- public stream-url builders (used by the registry / short-circuits) --

    override fun movieStreamUrl(acc: XtreamAccount, streamId: Int, ext: String): String = streamUrl(acc, "movie", streamId, ext.ifBlank { "mp4" })
    override fun liveStreamUrl(acc: XtreamAccount, streamId: Int): String = streamUrl(acc, "live", streamId, "ts")

    /**
     * Catch-up (tv_archive) replay URL — XUI's standard timeshift path form (the first entry of
     * [liveTimeshiftUrls]). Empty only for blank credentials, which [XtreamCatchUp.candidateUrls]
     * refuses to build garbage for — callers are Xtream-gated so it doesn't happen, but a crash
     * would be the worst answer.
     */
    fun liveTimeshiftUrl(acc: XtreamAccount, streamId: Int, startEpochMs: Long, durationMinutes: Int): String =
        liveTimeshiftUrls(acc, streamId, startEpochMs, durationMinutes).firstOrNull().orEmpty()

    /**
     * Every catch-up URL worth trying for this channel, best-known first — panels disagree about
     * the shape and none advertise which they speak, so the caller walks the list until one plays
     * (CatchUpDialectWalk owns the walking policy). The first entry is the form Tuvora has always
     * sent; the date maths and dialects live in [XtreamCatchUp] (KMP twin of NuvioTV's).
     */
    fun liveTimeshiftUrls(
        acc: XtreamAccount,
        streamId: Int,
        startEpochMs: Long,
        durationMinutes: Int,
        containerExtension: String? = null,
        serverOffsetMs: Long? = null,
    ): List<String> = XtreamCatchUp.candidateUrls(
        baseUrl = acc.baseUrl,
        username = acc.username,
        password = acc.password,
        streamId = streamId,
        startMs = startEpochMs,
        endMs = startEpochMs + durationMinutes * 60_000L,
        containerExtension = containerExtension,
        serverOffsetMs = serverOffsetMs,
    )

    override fun episodeStreamUrl(acc: XtreamAccount, episodeId: String, ext: String): String {
        val base = acc.baseUrl.trimEnd('/')
        return "$base/series/${acc.username.encodeURLPathPart()}/${acc.password.encodeURLPathPart()}/$episodeId.${ext.ifBlank { "mp4" }}"
    }

    // --- internals -----------------------------------------------------------

    /**
     * The client's whole-body transport, admission-checked (WP6): every player_api request this
     * client sends funnels through here or [streamArray]/[streamArrayInto], so the per-origin
     * breaker sees each panel request exactly once. A refused admission throws the policy's
     * distinct [PanelHostFastFailException] before any bytes move.
     */
    private suspend fun panelGetText(url: String, dnsProvider: String?): String =
        IptvPanelGuard.guard.guardedPanelRequest(url) { httpGetText(url, dnsProvider) }

    private fun String.splitCsv(): List<String> = split(",").mapNotNull { it.trim().ifBlank { null } }

    private suspend fun categories(acc: XtreamAccount, action: String): Result<List<XtreamCategory>> = call {
        streamArray(acc, playerApi(acc, action)) { o ->
            val id = o["category_id"].asStringOrNull() ?: return@streamArray null
            XtreamCategory(id, o["category_name"].asStringOrNull() ?: "")
        }
    }

    /** One VOD list entry -> domain model, every field read tolerantly. internal for tests. */
    internal fun parseVodItem(acc: XtreamAccount, o: JsonObject): XtreamMovie? {
        val id = o["stream_id"].asIntOrNull() ?: return null
        val ext = o["container_extension"].asStringOrNull()
        return XtreamMovie(
            streamId = id,
            name = o["name"].asStringOrNull() ?: "",
            poster = o["stream_icon"].asStringOrNull(),
            categoryId = o["category_id"].asStringOrNull(),
            rating = o["rating"].asStringOrNull(),
            streamUrl = streamUrl(acc, "movie", id, ext ?: "mp4"),
            tmdb = o["tmdb"].asIntOrNull()?.takeIf { it > 0 },
            containerExtension = ext,
        )
    }

    /** One series list entry -> domain model, every field read tolerantly. internal for tests. */
    internal fun parseSeriesItem(o: JsonObject): XtreamSeriesItem? {
        val id = o["series_id"].asIntOrNull() ?: return null
        return XtreamSeriesItem(
            seriesId = id,
            name = o["name"].asStringOrNull() ?: "",
            poster = o["cover"].asStringOrNull(),
            categoryId = o["category_id"].asStringOrNull(),
            plot = o["plot"].asStringOrNull(),
            rating = o["rating"].asStringOrNull(),
            tmdb = o["tmdb"].asIntOrNull()?.takeIf { it > 0 },
            year = (o["releaseDate"] ?: o["release_date"]).asStringOrNull()?.trim()?.take(4)?.toIntOrNull(),
        )
    }

    /**
     * Fetches a bulk list and maps it element by element, never holding the response whole.
     *
     * Every `player_api.php` array endpoint goes through here. [httpGetText] used to, and on a
     * large provider its single ~27 MB body allocation was enough to OOM the match-index build
     * outright — see [XtreamCatalogIndexParser] for the failure and the shape of the fix. The
     * mapping functions are unchanged, so field-level parsing behaves exactly as before.
     *
     * User-Agent is deliberately left null: that is what the panel saw on the [httpGetText]
     * path, and a UA a user configured for their M3U host is not necessarily one the panel
     * accepts.
     */
    private suspend fun <T> streamArray(
        acc: XtreamAccount,
        url: String,
        map: (JsonObject) -> T?,
    ): List<T> {
        val parser = XtreamCatalogIndexParser(json, map)
        // Guarded like panelGetText (WP6). A parser throw classifies as HTTP_RESPONSE — body
        // bytes arrived, which is all the breaker measures.
        IptvPanelGuard.guard.guardedPanelRequest(url) {
            httpStreamLines(url, userAgent = null, dnsProvider = acc.dnsProvider) { parser.accept(it) }
        }
        return parser.finish()
    }

    /** [streamArray] in sink mode: rows go to [onItem] as they parse; returns the count. */
    private suspend fun <T> streamArrayInto(
        acc: XtreamAccount,
        url: String,
        map: (JsonObject) -> T?,
        onItem: (T) -> Unit,
    ): Int {
        val parser = XtreamCatalogIndexParser(json, map, sink = onItem)
        IptvPanelGuard.guard.guardedPanelRequest(url) {
            httpStreamLines(url, userAgent = null, dnsProvider = acc.dnsProvider) { parser.accept(it) }
        }
        return parser.finishCount()
    }

    private fun playerApi(acc: XtreamAccount, action: String? = null, categoryId: String? = null): String {
        val base = acc.baseUrl.trimEnd('/')
        val sb = StringBuilder(base)
            .append("/player_api.php?username=").append(acc.username.encodeURLParameter())
            .append("&password=").append(acc.password.encodeURLParameter())
        if (action != null) sb.append("&action=").append(action)
        if (categoryId != null) sb.append("&category_id=").append(categoryId.encodeURLParameter())
        return sb.toString()
    }

    private fun streamUrl(acc: XtreamAccount, kind: String, id: Int, ext: String): String {
        val base = acc.baseUrl.trimEnd('/')
        return "$base/$kind/${acc.username.encodeURLPathPart()}/${acc.password.encodeURLPathPart()}/$id.$ext"
    }

    private inline fun <T> call(block: () -> T): Result<T> = runCatching { block() }

    private fun JsonObject?.orEmptyEntries(): Set<Map.Entry<String, JsonElement>> = this?.entries ?: emptySet()
    private fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
    private fun JsonElement?.asIntOrNull(): Int? {
        val p = this as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.contentOrNull?.trim()?.toIntOrNull()
    }
}

internal fun XtreamEpgEntryDto.toProgram(): XtreamProgram = XtreamProgram(
    title = decodeXtreamBase64(title),
    description = decodeXtreamBase64(description),
    startMs = (startTimestamp?.toLongOrNull() ?: 0L) * 1000,
    endMs = (stopTimestamp?.toLongOrNull() ?: 0L) * 1000,
    nowPlaying = nowPlaying == 1,
    // FlexInt already coerced "1"/true; any positive count is a mark, junk decoded to null stays
    // null — silence, not "no".
    hasArchive = hasArchive?.let { it > 0 }
)

/** Xtream base64-encodes EPG title/description. Returns "" on null/garbage rather than throwing. */
internal fun decodeXtreamBase64(s: String?): String {
    if (s.isNullOrBlank()) return ""
    return runCatching { s.trim().decodeBase64String() }.getOrDefault(s)
}
