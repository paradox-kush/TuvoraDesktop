package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.IptvSubtitleIdResolver
import com.nuvio.app.features.iptv.match.MatchKind
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.player.AddonSubtitleIdPolicy
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException

/**
 * F17: the public id OpenSubtitles (and any Stremio subtitle add-on) knows an Xtream movie/series
 * by. The item's TMDB id comes from the panel's bulk list (kept in the local match index) or, when
 * the index has no row for it yet (T5: it is built in the background, so a playlist added this
 * session, or a panel still indexing, has none and no subtitle was ever requested), from the
 * panel's own get_vod_info / get_series_info. TMDB's external-ids endpoint turns it into an IMDb id
 * (cached in TmdbService). Only the TMDB id is ever sent to TMDB — never the provider id; the panel
 * calls go to the user's own provider. M3U/Stalker items, items without a TMDB id, or a failed
 * lookup → null, and the player then asks no add-on.
 */
internal class XtreamSubtitleIdLookup(
    private val indexTmdb: suspend (accountId: String, isSeries: Boolean, streamId: Int) -> Int?,
    private val panelTmdb: suspend (accountId: String, isSeries: Boolean, streamId: Int) -> Int?,
    private val imdbOf: suspend (tmdbId: Int, isSeries: Boolean) -> String?,
) : IptvSubtitleIdResolver {
    override suspend fun publicSubtitleVideoId(parentMetaId: String, season: Int?, episode: Int?): String? {
        val parsed = XtreamItemRegistry.parseId(parentMetaId) ?: return null
        val sid = parsed.id.toIntOrNull() ?: return null
        val isSeries = when (parsed.kind) {
            XtreamKind.VOD -> false
            XtreamKind.SERIES -> true
            else -> return null
        }
        val tmdbId = safely { indexTmdb(parsed.accountId, isSeries, sid) }?.takeIf { it > 0 }
            ?: safely { panelTmdb(parsed.accountId, isSeries, sid) }?.takeIf { it > 0 }
            ?: return null
        val imdbId = safely { imdbOf(tmdbId, isSeries) }
        return AddonSubtitleIdPolicy.publicVideoId(imdbId, isSeries, season, episode)
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

internal object XtreamSubtitleIdResolver : IptvSubtitleIdResolver by XtreamSubtitleIdLookup(
    indexTmdb = { accountId, isSeries, sid ->
        XtreamMatchIndex.itemRow(accountId, if (isSeries) MatchKind.SERIES else MatchKind.MOVIE, sid)?.tmdb
    },
    panelTmdb = { accountId, isSeries, sid ->
        XtreamRepository.ensureLoaded()
        XtreamRepository.uiState.value.accounts
            .firstOrNull { it.id == accountId && it.sourceType == SOURCE_TYPE_XTREAM }
            ?.let { acc ->
                if (isSeries) XtreamClient.seriesInfo(acc, sid).getOrNull()?.tmdbId
                else XtreamClient.vodInfo(acc, sid).getOrNull()?.tmdbId
            }
    },
    imdbOf = { tmdbId, isSeries -> TmdbService.tmdbToImdb(tmdbId, if (isSeries) "tv" else "movie") },
)
