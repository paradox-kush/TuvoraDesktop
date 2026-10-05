package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.IptvSubtitleIdResolver
import com.nuvio.app.features.iptv.match.MatchKind
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.player.AddonSubtitleIdPolicy
import com.nuvio.app.features.tmdb.TmdbService

/**
 * F17: the public id OpenSubtitles (and any Stremio subtitle add-on) knows an Xtream movie/series
 * by. The panel's bulk list carries a TMDB id per item (kept in the local match index); TMDB's
 * external-ids endpoint turns it into an IMDb id (cached in TmdbService). Only the TMDB id is ever
 * sent to TMDB — never the provider id. M3U/Stalker items, items without a TMDB id, or a failed
 * lookup → null, and the player then asks no add-on.
 */
internal object XtreamSubtitleIdResolver : IptvSubtitleIdResolver {
    override suspend fun publicSubtitleVideoId(parentMetaId: String, season: Int?, episode: Int?): String? {
        val parsed = XtreamItemRegistry.parseId(parentMetaId) ?: return null
        val sid = parsed.id.toIntOrNull() ?: return null
        val (kind, isSeries) = when (parsed.kind) {
            XtreamKind.VOD -> MatchKind.MOVIE to false
            XtreamKind.SERIES -> MatchKind.SERIES to true
            else -> return null
        }
        val tmdbId = XtreamMatchIndex.itemRow(parsed.accountId, kind, sid)?.tmdb?.takeIf { it > 0 } ?: return null
        val imdbId = TmdbService.tmdbToImdb(tmdbId, if (isSeries) "tv" else "movie")
        return AddonSubtitleIdPolicy.publicVideoId(imdbId, isSeries, season, episode)
    }
}
