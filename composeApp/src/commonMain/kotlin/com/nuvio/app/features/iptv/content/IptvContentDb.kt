package com.nuvio.app.features.iptv.content

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private inline fun <R> SQLiteStatement.use(block: (SQLiteStatement) -> R): R =
    try { block(this) } finally { close() }

/** The three catalog kinds an M3U playlist is split into, keyed per playlist. */
internal enum class IptvContentKind(val slug: String) { LIVE("live"), VOD("vod"), SERIES("series") }

internal data class IptvCategoryRow(val id: String, val name: String)

/**
 * A live channel or VOD movie row. [ext] = container extension (VOD only). [cmd] is the Stalker
 * create_link handle (P6): stable across sessions — unlike the play URL, which is single-use —
 * so persisting it is what makes a browsed Stalker item playable after a cold start. [hasArchive]
 * = Stalker tv_archive (timeshift); M3U rows leave both defaulted. [useHttpTmpLink] /
 * [useLoadBalancing] mirror the Xtream panel's per-channel flags (stream resolution consumes
 * them — this store only persists and returns them).
 */
internal data class IptvStreamRow(
    val sid: Int,
    val name: String,
    val logo: String?,
    val tvgId: String?,
    val categoryId: String?,
    val url: String,
    val ext: String?,
    val cmd: String? = null,
    val hasArchive: Boolean = false,
    val useHttpTmpLink: Boolean = false,
    val useLoadBalancing: Boolean = false,
)

/** A series row (one per distinct series within a playlist). */
internal data class IptvSeriesRow(
    val sid: Int,
    val name: String,
    val logo: String?,
    val categoryId: String?,
)

/** One episode belonging to a series (grouped by [seriesSid]). [cmd] = the SEASON's create_link
 *  handle for Stalker (episodes play as season-cmd + series=<n>; the top-level series row's own
 *  cmd is empty on real portals), denormalized per episode row. */
internal data class IptvEpisodeRow(
    val seriesSid: Int,
    val episodeId: String,
    val name: String,
    val season: Int,
    val episode: Int,
    val logo: String?,
    val url: String,
    val ext: String?,
    val cmd: String? = null,
)

internal data class IngestMeta(
    val builtAtMs: Long,
    val liveCount: Int,
    val vodCount: Int,
    val seriesCount: Int,
    /** The M3U `url-tvg` / `x-tvg-url` header captured at ingest (EPG source when no explicit epgUrl). */
    val epgUrl: String? = null,
)

/** EPG freshness marker for a playlist — non-null once XMLTV has been ingested at least once. */
internal data class EpgMeta(val builtAtMs: Long, val programmeCount: Int)

/** One EPG programme row (already channel-filtered + UTC-normalized). [hasArchive] = the
 *  programme is inside the provider's replay window (catch-up). Windowed reads truncate
 *  [desc] to 600 chars — [IptvContentDb.epgFullDesc] fetches the whole text on demand. */
internal data class EpgProgrammeRow(
    val channelId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val desc: String?,
    val hasArchive: Boolean = false,
)

/**
 * On-disk store for parsed M3U catalogs. One row-set per `playlist_id` (the M3U account id). Mirrors
 * [com.nuvio.app.features.iptv.match.XtreamMatchIndex]: a single lazily-opened connection, all access
 * Mutex-guarded, schema tracked via `PRAGMA user_version`, and full rebuilds done as chunked
 * transactions with the meta row written LAST so a crashed ingest reads as "never ingested" rather
 * than "complete but partial".
 *
 * Every lookup is a single indexed SELECT — sub-ms even against a 611k-episode series table.
 */
internal object IptvContentDb {

    private val mutex = Mutex()
    private var conn: SQLiteConnection? = null

    private fun connection(): SQLiteConnection = conn ?: IptvContentDbDriver.openConnection().also {
        val version = it.prepare("PRAGMA user_version").use { st -> if (st.step()) st.getLong(0) else 0L }
        if (version < 1) {
            it.execSQL("DROP TABLE IF EXISTS channels")
            it.execSQL("DROP TABLE IF EXISTS vod")
            it.execSQL("DROP TABLE IF EXISTS series")
            it.execSQL("DROP TABLE IF EXISTS episodes")
            it.execSQL("DROP TABLE IF EXISTS categories")
            it.execSQL("DROP TABLE IF EXISTS ingest_meta")
            it.execSQL("PRAGMA user_version = 1")
        }
        it.execSQL("CREATE TABLE IF NOT EXISTS channels(playlist_id TEXT NOT NULL, sid INTEGER NOT NULL, category_id TEXT, name TEXT NOT NULL, logo TEXT, tvg_id TEXT, url TEXT NOT NULL, PRIMARY KEY(playlist_id, sid)) WITHOUT ROWID")
        it.execSQL("CREATE INDEX IF NOT EXISTS channels_cat ON channels(playlist_id, category_id)")
        it.execSQL("CREATE TABLE IF NOT EXISTS vod(playlist_id TEXT NOT NULL, sid INTEGER NOT NULL, category_id TEXT, name TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, ext TEXT, PRIMARY KEY(playlist_id, sid)) WITHOUT ROWID")
        it.execSQL("CREATE INDEX IF NOT EXISTS vod_cat ON vod(playlist_id, category_id)")
        it.execSQL("CREATE TABLE IF NOT EXISTS series(playlist_id TEXT NOT NULL, sid INTEGER NOT NULL, category_id TEXT, name TEXT NOT NULL, logo TEXT, PRIMARY KEY(playlist_id, sid)) WITHOUT ROWID")
        it.execSQL("CREATE INDEX IF NOT EXISTS series_cat ON series(playlist_id, category_id)")
        it.execSQL("CREATE TABLE IF NOT EXISTS episodes(playlist_id TEXT NOT NULL, series_sid INTEGER NOT NULL, episode_id TEXT NOT NULL, name TEXT NOT NULL, season INTEGER NOT NULL, episode INTEGER NOT NULL, logo TEXT, url TEXT NOT NULL, ext TEXT, PRIMARY KEY(playlist_id, episode_id)) WITHOUT ROWID")
        it.execSQL("CREATE INDEX IF NOT EXISTS episodes_series ON episodes(playlist_id, series_sid)")
        it.execSQL("CREATE TABLE IF NOT EXISTS categories(playlist_id TEXT NOT NULL, type TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(playlist_id, type, id)) WITHOUT ROWID")
        it.execSQL("CREATE TABLE IF NOT EXISTS ingest_meta(playlist_id TEXT NOT NULL PRIMARY KEY, built_at INTEGER NOT NULL, live_count INTEGER NOT NULL, vod_count INTEGER NOT NULL, series_count INTEGER NOT NULL) WITHOUT ROWID")
        // v2 (P2 XMLTV EPG): programme rows per playlist+channel, plus the M3U `url-tvg` captured at
        // ingest (stored on ingest_meta as an additive column so existing rows keep their old shape).
        if (version < 2) {
            it.execSQL("DROP TABLE IF EXISTS epg_programmes")
            it.execSQL("DROP TABLE IF EXISTS epg_meta")
            // Add the column to any pre-v2 ingest_meta row-set; ignore if it already exists.
            runCatching { it.execSQL("ALTER TABLE ingest_meta ADD COLUMN epg_url TEXT") }
            it.execSQL("PRAGMA user_version = 2")
        }
        // v3 (P6 Stalker→SQLite): the stable create_link handle per row + the timeshift flag, so a
        // browsed Stalker item survives a cold start. Additive columns; M3U rows leave them NULL.
        if (version < 3) {
            runCatching { it.execSQL("ALTER TABLE channels ADD COLUMN cmd TEXT") }
            runCatching { it.execSQL("ALTER TABLE channels ADD COLUMN tv_archive INTEGER") }
            runCatching { it.execSQL("ALTER TABLE vod ADD COLUMN cmd TEXT") }
            runCatching { it.execSQL("ALTER TABLE episodes ADD COLUMN cmd TEXT") }
            it.execSQL("PRAGMA user_version = 3")
        }
        it.execSQL("CREATE TABLE IF NOT EXISTS epg_programmes(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, desc TEXT)")
        it.execSQL("CREATE INDEX IF NOT EXISTS epg_lookup ON epg_programmes(playlist_id, channel_id, start_ms)")
        // Per-playlist EPG freshness marker (kept separate from the catalog's ingest_meta so an EPG
        // refresh doesn't touch the catalog row, and vice-versa).
        it.execSQL("CREATE TABLE IF NOT EXISTS epg_meta(playlist_id TEXT NOT NULL PRIMARY KEY, built_at INTEGER NOT NULL, programme_count INTEGER NOT NULL) WITHOUT ROWID")
        // v4 (memory/catch-up pre-work, one migration): the per-programme catch-up flag on the
        // guide rows, plus the Xtream panel's per-channel flags that stream resolution consumes.
        // Runs AFTER the CREATEs above so a fresh DB replays it against the just-created tables
        // (the v2/v3 idiom: base CREATEs stay at v1 shape, later columns arrive via ALTER).
        if (version < 4) {
            runCatching { it.execSQL("ALTER TABLE epg_programmes ADD COLUMN has_archive INTEGER NOT NULL DEFAULT 0") }
            runCatching { it.execSQL("ALTER TABLE channels ADD COLUMN use_http_tmp_link INTEGER") }
            runCatching { it.execSQL("ALTER TABLE channels ADD COLUMN use_load_balancing INTEGER") }
            it.execSQL("PRAGMA user_version = 4")
        }
        // Per-(playlist, channel) EPG fetch stamp — the guide's lazy-fetch gate (v4, but created
        // unconditionally like the other epg tables: IF NOT EXISTS is self-healing).
        it.execSQL("CREATE TABLE IF NOT EXISTS epg_channel_fetch(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, fetched_at INTEGER NOT NULL, PRIMARY KEY(playlist_id, channel_id)) WITHOUT ROWID")
        conn = it
    }

    private fun now(): Long = TraktPlatformClock.nowEpochMs()

    /** Non-null when a playlist has a completed ingest — the "already ingested" gate. */
    suspend fun ingestMeta(playlistId: String): IngestMeta? = mutex.withLock {
        connection().prepare("SELECT built_at, live_count, vod_count, series_count, epg_url FROM ingest_meta WHERE playlist_id = ?").use { st ->
            st.bindText(1, playlistId)
            if (st.step()) IngestMeta(
                builtAtMs = st.getLong(0),
                liveCount = st.getLong(1).toInt(),
                vodCount = st.getLong(2).toInt(),
                seriesCount = st.getLong(3).toInt(),
                epgUrl = if (st.isNull(4)) null else st.getText(4),
            ) else null
        }
    }

    // --- ingest (transactional, chunked, meta-last) ------------------------------

    /**
     * Wipes any prior rows for [playlistId] in one short transaction. Call once at the start of an
     * ingest; then stream [insertChunk] calls; then [finishIngest] writes the meta row LAST.
     */
    suspend fun beginIngest(playlistId: String) = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            for (table in listOf("channels", "vod", "series", "episodes", "categories", "ingest_meta", "epg_programmes", "epg_meta", "epg_channel_fetch")) {
                c.prepare("DELETE FROM $table WHERE playlist_id = ?").use { st -> st.bindText(1, playlistId); st.step() }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /**
     * Inserts one bounded batch of parsed rows in a single transaction (the caller flushes every
     * ~5k entries so the write lock stays short and RAM stays flat). Categories are upserted so a
     * category seen across many chunks is stored once.
     */
    suspend fun insertChunk(
        playlistId: String,
        channels: List<IptvStreamRow>,
        vod: List<IptvStreamRow>,
        series: List<IptvSeriesRow>,
        episodes: List<IptvEpisodeRow>,
        categories: List<Triple<String, String, String>>, // (type, id, name)
    ) = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            if (channels.isNotEmpty()) c.prepare("INSERT OR REPLACE INTO channels(playlist_id, sid, category_id, name, logo, tvg_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing) VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { st ->
                for (r in channels) {
                    st.reset()
                    st.bindText(1, playlistId); st.bindLong(2, r.sid.toLong())
                    if (r.categoryId != null) st.bindText(3, r.categoryId) else st.bindNull(3)
                    st.bindText(4, r.name)
                    if (r.logo != null) st.bindText(5, r.logo) else st.bindNull(5)
                    if (r.tvgId != null) st.bindText(6, r.tvgId) else st.bindNull(6)
                    st.bindText(7, r.url)
                    if (r.cmd != null) st.bindText(8, r.cmd) else st.bindNull(8)
                    st.bindLong(9, if (r.hasArchive) 1L else 0L)
                    st.bindLong(10, if (r.useHttpTmpLink) 1L else 0L)
                    st.bindLong(11, if (r.useLoadBalancing) 1L else 0L)
                    st.step()
                }
            }
            if (vod.isNotEmpty()) c.prepare("INSERT OR REPLACE INTO vod(playlist_id, sid, category_id, name, logo, url, ext, cmd) VALUES(?,?,?,?,?,?,?,?)").use { st ->
                for (r in vod) {
                    st.reset()
                    st.bindText(1, playlistId); st.bindLong(2, r.sid.toLong())
                    if (r.categoryId != null) st.bindText(3, r.categoryId) else st.bindNull(3)
                    st.bindText(4, r.name)
                    if (r.logo != null) st.bindText(5, r.logo) else st.bindNull(5)
                    st.bindText(6, r.url)
                    if (r.ext != null) st.bindText(7, r.ext) else st.bindNull(7)
                    if (r.cmd != null) st.bindText(8, r.cmd) else st.bindNull(8)
                    st.step()
                }
            }
            if (series.isNotEmpty()) c.prepare("INSERT OR REPLACE INTO series(playlist_id, sid, category_id, name, logo) VALUES(?,?,?,?,?)").use { st ->
                for (r in series) {
                    st.reset()
                    st.bindText(1, playlistId); st.bindLong(2, r.sid.toLong())
                    if (r.categoryId != null) st.bindText(3, r.categoryId) else st.bindNull(3)
                    st.bindText(4, r.name)
                    if (r.logo != null) st.bindText(5, r.logo) else st.bindNull(5)
                    st.step()
                }
            }
            if (episodes.isNotEmpty()) c.prepare("INSERT OR REPLACE INTO episodes(playlist_id, series_sid, episode_id, name, season, episode, logo, url, ext, cmd) VALUES(?,?,?,?,?,?,?,?,?,?)").use { st ->
                for (r in episodes) {
                    st.reset()
                    st.bindText(1, playlistId); st.bindLong(2, r.seriesSid.toLong()); st.bindText(3, r.episodeId)
                    st.bindText(4, r.name); st.bindLong(5, r.season.toLong()); st.bindLong(6, r.episode.toLong())
                    if (r.logo != null) st.bindText(7, r.logo) else st.bindNull(7)
                    st.bindText(8, r.url)
                    if (r.ext != null) st.bindText(9, r.ext) else st.bindNull(9)
                    if (r.cmd != null) st.bindText(10, r.cmd) else st.bindNull(10)
                    st.step()
                }
            }
            if (categories.isNotEmpty()) c.prepare("INSERT OR REPLACE INTO categories(playlist_id, type, id, name) VALUES(?,?,?,?)").use { st ->
                for ((type, id, name) in categories) {
                    st.reset()
                    st.bindText(1, playlistId); st.bindText(2, type); st.bindText(3, id); st.bindText(4, name)
                    st.step()
                }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** Writes the meta row LAST — its presence is the "ingest complete" signal. [epgUrl] = the M3U `url-tvg`. */
    suspend fun finishIngest(playlistId: String, liveCount: Int, vodCount: Int, seriesCount: Int, epgUrl: String? = null) = mutex.withLock {
        connection().prepare("INSERT OR REPLACE INTO ingest_meta(playlist_id, built_at, live_count, vod_count, series_count, epg_url) VALUES(?,?,?,?,?,?)").use { st ->
            st.bindText(1, playlistId); st.bindLong(2, now())
            st.bindLong(3, liveCount.toLong()); st.bindLong(4, vodCount.toLong()); st.bindLong(5, seriesCount.toLong())
            if (epgUrl != null) st.bindText(6, epgUrl) else st.bindNull(6)
            st.step()
        }
    }

    // --- EPG (XMLTV) -------------------------------------------------------------

    /** EPG freshness marker — non-null once XMLTV was ingested for this playlist. */
    suspend fun epgMeta(playlistId: String): EpgMeta? = mutex.withLock {
        connection().prepare("SELECT built_at, programme_count FROM epg_meta WHERE playlist_id = ?").use { st ->
            st.bindText(1, playlistId)
            if (st.step()) EpgMeta(st.getLong(0), st.getLong(1).toInt()) else null
        }
    }

    /**
     * The distinct, non-blank tvg-ids of a playlist's live channels — the allow-set the XMLTV parse
     * filters programmes against (so a 50-100 MB guide only stores rows for channels we actually have).
     */
    suspend fun distinctTvgIds(playlistId: String): List<String> = mutex.withLock {
        connection().prepare("SELECT DISTINCT tvg_id FROM channels WHERE playlist_id = ? AND tvg_id IS NOT NULL AND tvg_id <> ''").use { st ->
            st.bindText(1, playlistId)
            val out = ArrayList<String>()
            while (st.step()) if (!st.isNull(0)) out.add(st.getText(0))
            out
        }
    }

    /** Wipes any prior EPG rows for a playlist (per-channel fetch stamps included — a wholesale
     *  refresh supersedes them). Call once before streaming [insertEpgChunk] calls. */
    suspend fun beginEpg(playlistId: String) = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            for (table in listOf("epg_programmes", "epg_meta", "epg_channel_fetch")) {
                c.prepare("DELETE FROM $table WHERE playlist_id = ?").use { st -> st.bindText(1, playlistId); st.step() }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** Inserts one bounded batch of EPG programmes (caller flushes every ~5k to keep RAM flat). */
    suspend fun insertEpgChunk(playlistId: String, programmes: List<EpgProgrammeRow>) = mutex.withLock {
        if (programmes.isEmpty()) return@withLock
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.prepare("INSERT INTO epg_programmes(playlist_id, channel_id, start_ms, end_ms, title, desc, has_archive) VALUES(?,?,?,?,?,?,?)").use { st ->
                for (r in programmes) {
                    st.reset()
                    st.bindText(1, playlistId); st.bindText(2, r.channelId)
                    st.bindLong(3, r.startMs); st.bindLong(4, r.endMs)
                    st.bindText(5, r.title)
                    if (r.desc != null) st.bindText(6, r.desc) else st.bindNull(6)
                    st.bindLong(7, if (r.hasArchive) 1L else 0L)
                    st.step()
                }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /**
     * Atomic per-channel EPG refill: the channel's old rows are DELETEd in the SAME transaction
     * as the new batch's insert, and the (playlist, channel) fetch stamp is written with them —
     * a reader never sees an empty channel mid-refill and a crash leaves the old rows intact.
     * Rows are stored under [channelId] regardless of what their own field says: the refill is
     * per-channel by contract. An empty [programmes] still stamps [fetchedAtMs] so the guide's
     * lazy-fetch gate stops re-asking a channel the provider has no guide for.
     */
    suspend fun refillChannelEpg(
        playlistId: String,
        channelId: String,
        programmes: List<EpgProgrammeRow>,
        fetchedAtMs: Long,
    ) = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.prepare("DELETE FROM epg_programmes WHERE playlist_id = ? AND channel_id = ?").use { st ->
                st.bindText(1, playlistId); st.bindText(2, channelId); st.step()
            }
            if (programmes.isNotEmpty()) {
                c.prepare("INSERT INTO epg_programmes(playlist_id, channel_id, start_ms, end_ms, title, desc, has_archive) VALUES(?,?,?,?,?,?,?)").use { st ->
                    for (r in programmes) {
                        st.reset()
                        st.bindText(1, playlistId); st.bindText(2, channelId)
                        st.bindLong(3, r.startMs); st.bindLong(4, r.endMs)
                        st.bindText(5, r.title)
                        if (r.desc != null) st.bindText(6, r.desc) else st.bindNull(6)
                        st.bindLong(7, if (r.hasArchive) 1L else 0L)
                        st.step()
                    }
                }
            }
            c.prepare("INSERT OR REPLACE INTO epg_channel_fetch(playlist_id, channel_id, fetched_at) VALUES(?,?,?)").use { st ->
                st.bindText(1, playlistId); st.bindText(2, channelId); st.bindLong(3, fetchedAtMs)
                st.step()
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** When this channel's EPG was last refilled (null = never — the lazy-fetch gate opens). */
    suspend fun epgChannelFetchedAt(playlistId: String, channelId: String): Long? = mutex.withLock {
        connection().prepare("SELECT fetched_at FROM epg_channel_fetch WHERE playlist_id = ? AND channel_id = ?").use { st ->
            st.bindText(1, playlistId); st.bindText(2, channelId)
            if (st.step()) st.getLong(0) else null
        }
    }

    /** Drops programmes that ended before [cutoffMs] — the guide never reads that far back. */
    suspend fun pruneEpg(playlistId: String, cutoffMs: Long) = mutex.withLock {
        connection().prepare("DELETE FROM epg_programmes WHERE playlist_id = ? AND end_ms < ?").use { st ->
            st.bindText(1, playlistId); st.bindLong(2, cutoffMs)
            st.step()
        }
    }

    /**
     * Windowed guide read: programmes overlapping [fromMs, toMs) for one channel, ordered by
     * start, desc truncated to its first 600 chars (SUBSTR runs in SQLite, so a feed's 4KB
     * synopsis never lands in the heap — [epgFullDesc] fetches the whole text on demand).
     * [limit] keeps a corrupt feed from materializing thousands of rows.
     */
    suspend fun epgWindow(
        playlistId: String,
        channelId: String,
        fromMs: Long,
        toMs: Long,
        limit: Int = 200,
    ): List<EpgProgrammeRow> = mutex.withLock {
        connection().prepare(
            "SELECT channel_id, start_ms, end_ms, title, SUBSTR(desc, 1, 600), has_archive FROM epg_programmes " +
                "WHERE playlist_id = ? AND channel_id = ? AND start_ms < ? AND end_ms > ? ORDER BY start_ms LIMIT ?"
        ).use { st ->
            st.bindText(1, playlistId); st.bindText(2, channelId)
            st.bindLong(3, toMs); st.bindLong(4, fromMs); st.bindLong(5, limit.toLong())
            val out = ArrayList<EpgProgrammeRow>()
            while (st.step()) out.add(
                EpgProgrammeRow(
                    channelId = st.getText(0),
                    startMs = st.getLong(1),
                    endMs = st.getLong(2),
                    title = st.getText(3),
                    desc = if (st.isNull(4)) null else st.getText(4),
                    hasArchive = st.getLong(5) > 0,
                )
            )
            out
        }
    }

    /** The FULL description of one programme (keyed by its start) — the details sheet's lazy read. */
    suspend fun epgFullDesc(playlistId: String, channelId: String, startMs: Long): String? = mutex.withLock {
        connection().prepare(
            "SELECT desc FROM epg_programmes WHERE playlist_id = ? AND channel_id = ? AND start_ms = ? LIMIT 1"
        ).use { st ->
            st.bindText(1, playlistId); st.bindText(2, channelId); st.bindLong(3, startMs)
            if (st.step() && !st.isNull(0)) st.getText(0) else null
        }
    }

    /** Writes the EPG meta row LAST — its presence is the "EPG ingest complete" signal. */
    suspend fun finishEpg(playlistId: String, programmeCount: Int) = mutex.withLock {
        connection().prepare("INSERT OR REPLACE INTO epg_meta(playlist_id, built_at, programme_count) VALUES(?,?,?)").use { st ->
            st.bindText(1, playlistId); st.bindLong(2, now()); st.bindLong(3, programmeCount.toLong())
            st.step()
        }
    }

    /**
     * The programmes airing at/after [atMs] for one channel, ordered by start — the caller takes the
     * first (now) + second (next). A tiny bounded read: the covering index makes it a range scan.
     */
    /**
     * Programmes in a time window whose title or description mentions any of [tokens].
     *
     * The provider's own guide, searched in BULK — the counterpart to the EPG mirror's
     * programmesInWindow. Sports matching had no way to ask "which of my channels is showing
     * this match?" of the provider's EPG: the only entry point was per-channel, so the matcher
     * fell back to a network call per channel and had to gate that behind a channel-NAME
     * filter to stay affordable. A channel whose name says nothing useful was therefore never
     * asked, even when this table already knew it was airing the fixture.
     *
     * Bounded by the window (a few hours), so the scan stays small even on a huge panel.
     */
    suspend fun epgSearch(
        playlistId: String,
        tokens: List<String>,
        fromMs: Long,
        toMs: Long,
        limit: Int = 400,
    ): List<EpgProgrammeRow> = mutex.withLock {
        if (tokens.isEmpty()) return@withLock emptyList()
        val terms = tokens.take(8).map { "%${it.lowercase()}%" }
        val where = terms.joinToString(" OR ") { "(lower(title) LIKE ? OR lower(coalesce(desc,'')) LIKE ?)" }
        connection().prepare(
            "SELECT channel_id, start_ms, end_ms, title, desc, has_archive FROM epg_programmes " +
                "WHERE playlist_id = ? AND start_ms < ? AND end_ms > ? AND ($where) " +
                "ORDER BY start_ms LIMIT ?"
        ).use { st ->
            var i = 1
            st.bindText(i++, playlistId); st.bindLong(i++, toMs); st.bindLong(i++, fromMs)
            terms.forEach { t -> st.bindText(i++, t); st.bindText(i++, t) }
            st.bindLong(i, limit.toLong())
            val out = ArrayList<EpgProgrammeRow>()
            while (st.step()) out.add(
                EpgProgrammeRow(
                    channelId = st.getText(0),
                    startMs = st.getLong(1),
                    endMs = st.getLong(2),
                    title = st.getText(3),
                    desc = if (st.isNull(4)) null else st.getText(4),
                    hasArchive = st.getLong(5) > 0,
                )
            )
            out
        }
    }

    suspend fun epgAround(playlistId: String, channelId: String, atMs: Long, limit: Int): List<EpgProgrammeRow> = mutex.withLock {
        // Grab the currently-airing programme (start <= now < end) plus the upcoming ones. Union keeps
        // it a single indexed pass without pulling the channel's whole day.
        connection().prepare(
            "SELECT channel_id, start_ms, end_ms, title, desc, has_archive FROM epg_programmes " +
                "WHERE playlist_id = ? AND channel_id = ? AND end_ms > ? ORDER BY start_ms LIMIT ?"
        ).use { st ->
            st.bindText(1, playlistId); st.bindText(2, channelId); st.bindLong(3, atMs); st.bindLong(4, limit.toLong())
            val out = ArrayList<EpgProgrammeRow>()
            while (st.step()) out.add(
                EpgProgrammeRow(
                    channelId = st.getText(0),
                    startMs = st.getLong(1),
                    endMs = st.getLong(2),
                    title = st.getText(3),
                    desc = if (st.isNull(4)) null else st.getText(4),
                    hasArchive = st.getLong(5) > 0,
                )
            )
            out
        }
    }

    /** Drops every row for a playlist (used when an M3U account is removed/edited to a new URL). */
    suspend fun clear(playlistId: String) = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            for (table in listOf("channels", "vod", "series", "episodes", "categories", "ingest_meta", "epg_programmes", "epg_meta", "epg_channel_fetch")) {
                c.prepare("DELETE FROM $table WHERE playlist_id = ?").use { st -> st.bindText(1, playlistId); st.step() }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    // --- queries (all single indexed SELECTs) ------------------------------------

    suspend fun categoriesFor(playlistId: String, kind: IptvContentKind): List<IptvCategoryRow> = mutex.withLock {
        connection().prepare("SELECT id, name FROM categories WHERE playlist_id = ? AND type = ? ORDER BY name").use { st ->
            st.bindText(1, playlistId); st.bindText(2, kind.slug)
            val out = ArrayList<IptvCategoryRow>()
            while (st.step()) out.add(IptvCategoryRow(st.getText(0), st.getText(1)))
            out
        }
    }

    /**
     * Windowed reads (item 5): [limit] rows from [offset], name-ordered — the hub loads a first
     * window and appends on row-end instead of materializing a 10k-row category as one List
     * (which is how "M3U has a DB" still bloated the heap: storage without paging).
     */
    suspend fun pageChannels(playlistId: String, categoryId: String?, offset: Int, limit: Int): List<IptvStreamRow> = mutex.withLock {
        pagedStreamRows("channels", playlistId, categoryId, hasExt = false, offset = offset, limit = limit)
    }

    suspend fun pageVod(playlistId: String, categoryId: String?, offset: Int, limit: Int): List<IptvStreamRow> = mutex.withLock {
        pagedStreamRows("vod", playlistId, categoryId, hasExt = true, offset = offset, limit = limit)
    }

    suspend fun pageSeries(playlistId: String, categoryId: String?, offset: Int, limit: Int): List<IptvSeriesRow> = mutex.withLock {
        val sql = if (categoryId == null)
            "SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? ORDER BY name, sid LIMIT ? OFFSET ?"
        else
            "SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? AND category_id = ? ORDER BY name, sid LIMIT ? OFFSET ?"
        connection().prepare(sql).use { st ->
            st.bindText(1, playlistId)
            var i = 2
            if (categoryId != null) st.bindText(i++, categoryId)
            st.bindLong(i++, limit.toLong()); st.bindLong(i, offset.toLong())
            val out = ArrayList<IptvSeriesRow>()
            while (st.step()) out.add(
                IptvSeriesRow(
                    sid = st.getLong(0).toInt(),
                    name = st.getText(1),
                    logo = if (st.isNull(2)) null else st.getText(2),
                    categoryId = if (st.isNull(3)) null else st.getText(3),
                )
            )
            out
        }
    }

    private fun pagedStreamRows(table: String, playlistId: String, categoryId: String?, hasExt: Boolean, offset: Int, limit: Int): List<IptvStreamRow> {
        val extCol = if (hasExt) "ext" else "NULL"
        val tvgCol = if (table == "channels") "tvg_id" else "NULL"
        val archiveCol = if (table == "channels") "tv_archive" else "NULL"
        val tmpLinkCol = if (table == "channels") "use_http_tmp_link" else "NULL"
        val lbCol = if (table == "channels") "use_load_balancing" else "NULL"
        val sql = if (categoryId == null)
            "SELECT sid, name, logo, $tvgCol, category_id, url, $extCol, cmd, $archiveCol, $tmpLinkCol, $lbCol FROM $table WHERE playlist_id = ? ORDER BY name, sid LIMIT ? OFFSET ?"
        else
            "SELECT sid, name, logo, $tvgCol, category_id, url, $extCol, cmd, $archiveCol, $tmpLinkCol, $lbCol FROM $table WHERE playlist_id = ? AND category_id = ? ORDER BY name, sid LIMIT ? OFFSET ?"
        return connection().prepare(sql).use { st ->
            st.bindText(1, playlistId)
            var i = 2
            if (categoryId != null) st.bindText(i++, categoryId)
            st.bindLong(i++, limit.toLong()); st.bindLong(i, offset.toLong())
            val out = ArrayList<IptvStreamRow>()
            while (st.step()) out.add(
                IptvStreamRow(
                    sid = st.getLong(0).toInt(),
                    name = st.getText(1),
                    logo = if (st.isNull(2)) null else st.getText(2),
                    tvgId = if (st.isNull(3)) null else st.getText(3),
                    categoryId = if (st.isNull(4)) null else st.getText(4),
                    url = st.getText(5),
                    ext = if (st.isNull(6)) null else st.getText(6),
                    cmd = if (st.isNull(7)) null else st.getText(7),
                    hasArchive = !st.isNull(8) && st.getLong(8) > 0,
                    useHttpTmpLink = !st.isNull(9) && st.getLong(9) > 0,
                    useLoadBalancing = !st.isNull(10) && st.getLong(10) > 0,
                )
            )
            out
        }
    }

    suspend fun channelsFor(playlistId: String, categoryId: String?): List<IptvStreamRow> = mutex.withLock {
        streamRows("channels", playlistId, categoryId, hasExt = false)
    }

    suspend fun vodFor(playlistId: String, categoryId: String?): List<IptvStreamRow> = mutex.withLock {
        streamRows("vod", playlistId, categoryId, hasExt = true)
    }

    private fun streamRows(table: String, playlistId: String, categoryId: String?, hasExt: Boolean): List<IptvStreamRow> {
        val extCol = if (hasExt) "ext" else "NULL"
        val tvgCol = if (table == "channels") "tvg_id" else "NULL"
        val archiveCol = if (table == "channels") "tv_archive" else "NULL"
        val tmpLinkCol = if (table == "channels") "use_http_tmp_link" else "NULL"
        val lbCol = if (table == "channels") "use_load_balancing" else "NULL"
        val sql = if (categoryId == null)
            "SELECT sid, name, logo, $tvgCol, category_id, url, $extCol, cmd, $archiveCol, $tmpLinkCol, $lbCol FROM $table WHERE playlist_id = ? ORDER BY name"
        else
            "SELECT sid, name, logo, $tvgCol, category_id, url, $extCol, cmd, $archiveCol, $tmpLinkCol, $lbCol FROM $table WHERE playlist_id = ? AND category_id = ? ORDER BY name"
        return connection().prepare(sql).use { st ->
            st.bindText(1, playlistId)
            if (categoryId != null) st.bindText(2, categoryId)
            val out = ArrayList<IptvStreamRow>()
            while (st.step()) out.add(
                IptvStreamRow(
                    sid = st.getLong(0).toInt(),
                    name = st.getText(1),
                    logo = if (st.isNull(2)) null else st.getText(2),
                    tvgId = if (st.isNull(3)) null else st.getText(3),
                    categoryId = if (st.isNull(4)) null else st.getText(4),
                    url = st.getText(5),
                    ext = if (st.isNull(6)) null else st.getText(6),
                    cmd = if (st.isNull(7)) null else st.getText(7),
                    hasArchive = !st.isNull(8) && st.getLong(8) > 0,
                    useHttpTmpLink = !st.isNull(9) && st.getLong(9) > 0,
                    useLoadBalancing = !st.isNull(10) && st.getLong(10) > 0,
                )
            )
            out
        }
    }

    suspend fun seriesFor(playlistId: String, categoryId: String?): List<IptvSeriesRow> = mutex.withLock {
        val sql = if (categoryId == null)
            "SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? ORDER BY name"
        else
            "SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? AND category_id = ? ORDER BY name"
        connection().prepare(sql).use { st ->
            st.bindText(1, playlistId)
            if (categoryId != null) st.bindText(2, categoryId)
            val out = ArrayList<IptvSeriesRow>()
            while (st.step()) out.add(
                IptvSeriesRow(
                    sid = st.getLong(0).toInt(),
                    name = st.getText(1),
                    logo = if (st.isNull(2)) null else st.getText(2),
                    categoryId = if (st.isNull(3)) null else st.getText(3),
                )
            )
            out
        }
    }

    /** All episodes of one series, ordered season→episode — backs synthetic get_series_info. */
    suspend fun episodesFor(playlistId: String, seriesSid: Int): List<IptvEpisodeRow> = mutex.withLock {
        connection().prepare("SELECT series_sid, episode_id, name, season, episode, logo, url, ext, cmd FROM episodes WHERE playlist_id = ? AND series_sid = ? ORDER BY season, episode").use { st ->
            st.bindText(1, playlistId); st.bindLong(2, seriesSid.toLong())
            val out = ArrayList<IptvEpisodeRow>()
            while (st.step()) out.add(
                IptvEpisodeRow(
                    seriesSid = st.getLong(0).toInt(),
                    episodeId = st.getText(1),
                    name = st.getText(2),
                    season = st.getLong(3).toInt(),
                    episode = st.getLong(4).toInt(),
                    logo = if (st.isNull(5)) null else st.getText(5),
                    url = st.getText(6),
                    ext = if (st.isNull(7)) null else st.getText(7),
                    cmd = if (st.isNull(8)) null else st.getText(8),
                )
            )
            out
        }
    }

    /**
     * Replaces one playlist's LIVE lineup + live categories in a single transaction, leaving the
     * VOD/series write-through rows and the EPG untouched — the Stalker mirror path (P6): the
     * whole lineup arrives in one get_all_channels, so it refreshes wholesale, while VOD can only
     * ever accumulate page by page. The ingest_meta row doubles as the lineup freshness marker
     * (Stalker playlists never run the M3U ingest, so there is no collision).
     */
    suspend fun replaceLiveLineup(
        playlistId: String,
        channels: List<IptvStreamRow>,
        categories: List<Pair<String, String>>, // (id, name), type = live
    ) = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.prepare("DELETE FROM channels WHERE playlist_id = ?").use { st -> st.bindText(1, playlistId); st.step() }
            c.prepare("DELETE FROM categories WHERE playlist_id = ? AND type = ?").use { st ->
                st.bindText(1, playlistId); st.bindText(2, IptvContentKind.LIVE.slug); st.step()
            }
            c.prepare("INSERT OR REPLACE INTO channels(playlist_id, sid, category_id, name, logo, tvg_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing) VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { st ->
                for (r in channels) {
                    st.reset()
                    st.bindText(1, playlistId); st.bindLong(2, r.sid.toLong())
                    if (r.categoryId != null) st.bindText(3, r.categoryId) else st.bindNull(3)
                    st.bindText(4, r.name)
                    if (r.logo != null) st.bindText(5, r.logo) else st.bindNull(5)
                    if (r.tvgId != null) st.bindText(6, r.tvgId) else st.bindNull(6)
                    st.bindText(7, r.url)
                    if (r.cmd != null) st.bindText(8, r.cmd) else st.bindNull(8)
                    st.bindLong(9, if (r.hasArchive) 1L else 0L)
                    st.bindLong(10, if (r.useHttpTmpLink) 1L else 0L)
                    st.bindLong(11, if (r.useLoadBalancing) 1L else 0L)
                    st.step()
                }
            }
            c.prepare("INSERT OR REPLACE INTO categories(playlist_id, type, id, name) VALUES(?,?,?,?)").use { st ->
                for ((id, name) in categories) {
                    st.reset()
                    st.bindText(1, playlistId); st.bindText(2, IptvContentKind.LIVE.slug)
                    st.bindText(3, id); st.bindText(4, name)
                    st.step()
                }
            }
            // Freshness marker LAST, inside the same transaction: present+fresh = lineup usable.
            c.prepare("INSERT OR REPLACE INTO ingest_meta(playlist_id, built_at, live_count, vod_count, series_count, epg_url) VALUES(?,?,?,0,0,NULL)").use { st ->
                st.bindText(1, playlistId); st.bindLong(2, now()); st.bindLong(3, channels.size.toLong())
                st.step()
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** One episode's stored stream URL (+ ext) by its string id — for building the play stream. */
    suspend fun episodeUrl(playlistId: String, episodeId: String): Pair<String, String?>? = mutex.withLock {
        connection().prepare("SELECT url, ext FROM episodes WHERE playlist_id = ? AND episode_id = ?").use { st ->
            st.bindText(1, playlistId); st.bindText(2, episodeId)
            if (st.step()) st.getText(0) to (if (st.isNull(1)) null else st.getText(1)) else null
        }
    }

    /** The single series row for a sid (series-name lookup when building meta). */
    suspend fun seriesRow(playlistId: String, seriesSid: Int): IptvSeriesRow? = mutex.withLock {
        connection().prepare("SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? AND sid = ?").use { st ->
            st.bindText(1, playlistId); st.bindLong(2, seriesSid.toLong())
            if (st.step()) IptvSeriesRow(
                sid = st.getLong(0).toInt(),
                name = st.getText(1),
                logo = if (st.isNull(2)) null else st.getText(2),
                categoryId = if (st.isNull(3)) null else st.getText(3),
            ) else null
        }
    }

    /** A single VOD row by sid — rebuilds a movie's stream URL (or Stalker cmd) after a cold launch. */
    suspend fun vodRow(playlistId: String, sid: Int): IptvStreamRow? = mutex.withLock {
        connection().prepare("SELECT sid, name, logo, NULL, category_id, url, ext, cmd FROM vod WHERE playlist_id = ? AND sid = ?").use { st ->
            st.bindText(1, playlistId); st.bindLong(2, sid.toLong())
            if (st.step()) IptvStreamRow(
                sid = st.getLong(0).toInt(),
                name = st.getText(1),
                logo = if (st.isNull(2)) null else st.getText(2),
                tvgId = null,
                categoryId = if (st.isNull(4)) null else st.getText(4),
                url = st.getText(5),
                ext = if (st.isNull(6)) null else st.getText(6),
                cmd = if (st.isNull(7)) null else st.getText(7),
            ) else null
        }
    }

    /** A single channel row by sid — rebuilds a favorited channel's URL (or Stalker cmd) after a cold launch. */
    suspend fun channelRow(playlistId: String, sid: Int): IptvStreamRow? = mutex.withLock {
        connection().prepare("SELECT sid, name, logo, tvg_id, category_id, url, NULL, cmd, tv_archive, use_http_tmp_link, use_load_balancing FROM channels WHERE playlist_id = ? AND sid = ?").use { st ->
            st.bindText(1, playlistId); st.bindLong(2, sid.toLong())
            if (st.step()) IptvStreamRow(
                sid = st.getLong(0).toInt(),
                name = st.getText(1),
                logo = if (st.isNull(2)) null else st.getText(2),
                tvgId = if (st.isNull(3)) null else st.getText(3),
                categoryId = if (st.isNull(4)) null else st.getText(4),
                url = st.getText(5),
                ext = null,
                cmd = if (st.isNull(7)) null else st.getText(7),
                hasArchive = !st.isNull(8) && st.getLong(8) > 0,
                useHttpTmpLink = !st.isNull(9) && st.getLong(9) > 0,
                useLoadBalancing = !st.isNull(10) && st.getLong(10) > 0,
            ) else null
        }
    }

    /** Substring name search within one playlist + kind — backs the IPTV rows in Search. */
    suspend fun searchByName(playlistId: String, kind: IptvContentKind, query: String, limit: Int): List<IptvStreamRow> = mutex.withLock {
        when (kind) {
            IptvContentKind.LIVE -> searchStreams("channels", playlistId, query, limit, hasExt = false, hasTvg = true)
            IptvContentKind.VOD -> searchStreams("vod", playlistId, query, limit, hasExt = true, hasTvg = false)
            IptvContentKind.SERIES -> searchSeries(playlistId, query, limit)
        }
    }

    private fun searchStreams(table: String, playlistId: String, query: String, limit: Int, hasExt: Boolean, hasTvg: Boolean): List<IptvStreamRow> {
        val extCol = if (hasExt) "ext" else "NULL"
        val tvgCol = if (hasTvg) "tvg_id" else "NULL"
        return connection().prepare(
            "SELECT sid, name, logo, $tvgCol, category_id, url, $extCol FROM $table WHERE playlist_id = ? AND name LIKE '%' || ? || '%' LIMIT ?"
        ).use { st ->
            st.bindText(1, playlistId); st.bindText(2, query); st.bindLong(3, limit.toLong())
            val out = ArrayList<IptvStreamRow>()
            while (st.step()) out.add(
                IptvStreamRow(
                    sid = st.getLong(0).toInt(),
                    name = st.getText(1),
                    logo = if (st.isNull(2)) null else st.getText(2),
                    tvgId = if (st.isNull(3)) null else st.getText(3),
                    categoryId = if (st.isNull(4)) null else st.getText(4),
                    url = st.getText(5),
                    ext = if (st.isNull(6)) null else st.getText(6),
                )
            )
            out
        }
    }

    /** Series search returns stream rows with the series sid so callers register + link them. */
    private fun searchSeries(playlistId: String, query: String, limit: Int): List<IptvStreamRow> =
        connection().prepare("SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? AND name LIKE '%' || ? || '%' LIMIT ?").use { st ->
            st.bindText(1, playlistId); st.bindText(2, query); st.bindLong(3, limit.toLong())
            val out = ArrayList<IptvStreamRow>()
            while (st.step()) out.add(
                IptvStreamRow(
                    sid = st.getLong(0).toInt(),
                    name = st.getText(1),
                    logo = if (st.isNull(2)) null else st.getText(2),
                    tvgId = null,
                    categoryId = if (st.isNull(3)) null else st.getText(3),
                    url = "",
                    ext = null,
                )
            )
            out
        }
}
