package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.identity.M3uIdentity
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry

/**
 * B64 phase 3 — the pure re-key of a profile's saved M3U content ids onto the login-free item ids.
 *
 * Before B64 a phone/desktop M3U item id was FNV(raw stream URL) — with the login inside the URL — and
 * a "Show S01E02" `/movie/` row was a movie. Now ids hash the LOGIN-FREE URL ([M3uIdentity.itemSid]) and
 * such rows are episodes ([com.nuvio.app.features.iptv.identity.M3uSeriesGrouping]). The old id of every
 * catalog line is a pure function of its stored URL, so the plan is built from the catalog as it is
 * (either scheme) — no snapshot needed:
 *
 *  - renames: `live:`/`vod:` sid(url) -> itemSid(url, login); `episode:` hex(sid(url)) -> hex(itemSid);
 *    identical ids are left out (a URL without a login keeps its id, so most plans are tiny);
 *  - promotions: the old `vod:` id of a line that is now an episode -> that episode (library: the show;
 *    progress / watched: the episode under the show, with its season + episode).
 *
 * Applying the plan twice is a no-op (every target id is a fixpoint), so it runs after every ingest of
 * an M3U playlist with no marker; nothing is ever dropped — an id the plan does not know stays as it is.
 * The playlist part of a content id is left alone: Step 0's key adoption re-keys it independently, so
 * the two compose in either order.
 */
internal object M3uIdRekey {

    data class EpisodeLine(val url: String, val seriesSid: Int, val season: Int, val episode: Int, val seriesName: String)

    data class Promoted(val seriesId: String, val episodeId: String, val season: Int, val episode: Int, val seriesName: String)

    class Plan(val renames: Map<String, String>, val promoted: Map<String, Promoted>) {
        val isEmpty: Boolean get() = renames.isEmpty() && promoted.isEmpty()
        val size: Int get() = renames.size + promoted.size

        /** [item] re-keyed, or null when the plan does not touch it. */
        fun library(item: LibraryItem): LibraryItem? {
            renames[item.id]?.let { return item.copy(id = it) }
            val p = promoted[item.id] ?: return null
            return item.copy(id = p.seriesId, type = "series", name = p.seriesName)
        }

        fun progress(entry: WatchProgressEntry): WatchProgressEntry? {
            promoted[entry.videoId]?.let { p ->
                return entry.copy(
                    contentType = "series",
                    parentMetaId = p.seriesId,
                    parentMetaType = "series",
                    videoId = p.episodeId,
                    seasonNumber = p.season,
                    episodeNumber = p.episode,
                    episodeTitle = entry.episodeTitle ?: entry.title,
                    title = p.seriesName,
                    lastSourceUrl = null,
                    progressKey = null,
                )
            }
            val video = renames[entry.videoId]
            val parent = renames[entry.parentMetaId]
            if (video == null && parent == null) return null
            // progressKey is rebuilt from the new ids: a stale one would keep the old (login-bearing) id
            // as the server row's identity.
            return entry.copy(
                videoId = video ?: entry.videoId,
                parentMetaId = parent ?: entry.parentMetaId,
                lastSourceUrl = null,
                progressKey = null,
            )
        }

        fun watched(item: WatchedItem): WatchedItem? {
            promoted[item.id]?.let { p ->
                return item.copy(id = p.seriesId, type = "series", name = p.seriesName, season = p.season, episode = p.episode, videoId = p.episodeId)
            }
            val id = renames[item.id]
            val video = item.videoId?.let { renames[it] }
            if (id == null && video == null) return null
            return item.copy(id = id ?: item.id, videoId = video ?: item.videoId)
        }

        fun contentId(id: String): String? = renames[id]
    }

    fun plan(
        playlistId: String,
        login: M3uIdentity.Login?,
        channelUrls: List<String>,
        vodUrls: List<String>,
        episodes: List<EpisodeLine>,
    ): Plan {
        val renames = LinkedHashMap<String, String>()
        fun rename(old: String, new: String) { if (old != new) renames[old] = new }
        for (url in channelUrls) {
            rename(XtreamItemRegistry.liveId(playlistId, M3uIdentity.sidOf(url)), XtreamItemRegistry.liveId(playlistId, M3uIdentity.itemSid(url, login)))
        }
        val vodOldIds = HashSet<String>()
        for (url in vodUrls) {
            val old = XtreamItemRegistry.vodId(playlistId, M3uIdentity.sidOf(url))
            vodOldIds += old
            rename(old, XtreamItemRegistry.vodId(playlistId, M3uIdentity.itemSid(url, login)))
        }
        val promoted = LinkedHashMap<String, Promoted>()
        for (ep in episodes) {
            val newEpisode = XtreamItemRegistry.episodeId(playlistId, M3uIdentity.episodeId(ep.url, login))
            rename(XtreamItemRegistry.episodeId(playlistId, M3uIdentity.sidOf(ep.url).toString(16)), newEpisode)
            val oldVod = XtreamItemRegistry.vodId(playlistId, M3uIdentity.sidOf(ep.url))
            if (oldVod !in vodOldIds) {
                promoted[oldVod] = Promoted(
                    seriesId = XtreamItemRegistry.seriesId(playlistId, ep.seriesSid),
                    episodeId = newEpisode,
                    season = ep.season,
                    episode = ep.episode,
                    seriesName = ep.seriesName,
                )
            }
        }
        return Plan(renames, promoted)
    }
}
