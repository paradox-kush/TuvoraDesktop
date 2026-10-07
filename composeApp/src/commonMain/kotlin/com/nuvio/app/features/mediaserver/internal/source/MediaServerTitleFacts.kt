package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy
import com.nuvio.app.features.tmdb.TmdbService

/**
 * Title facts from TMDB for a title-page id (`tt…`, `tmdb:…`, a bare number; `:season:episode` tails ignored): the
 * ids a server item can carry and the names a library may hold it under. TMDB's own caches make repeated asks free.
 * An IMDb id still works without TMDB (Emby can filter on it); Jellyfin then has no title to search and offers nothing.
 */
internal object TmdbMatchTitleFacts : MatchTitleFacts {
    override suspend fun facts(videoId: String, type: String): MatchLookupPolicy.TitleFacts? {
        val head = videoId.removePrefix("tmdb:").removePrefix("movie:").removePrefix("series:").substringBefore(':').substringBefore('/').trim()
        if (head.isBlank()) return null
        val imdbFromId = head.takeIf { it.startsWith("tt", ignoreCase = true) }?.lowercase()
        val tmdb = runCatching { TmdbService.ensureTmdbId(videoId, type) }.getOrNull()?.takeIf { it.isNotBlank() }
        val tmdbInt = tmdb?.toIntOrNull()
        val bundle = tmdbInt?.let { runCatching { TmdbService.titleBundle(it, type) }.getOrNull() }
        val imdb = imdbFromId ?: tmdbInt?.let { runCatching { TmdbService.tmdbToImdb(it, type) }.getOrNull() }?.lowercase()
        val ids = MatchLookupPolicy.ExternalIds(tmdb = tmdb, imdb = imdb)
        if (!ids.hasAny) return null
        return MatchLookupPolicy.TitleFacts(
            ids = ids,
            primary = bundle?.primary,
            original = bundle?.original,
            alternatives = bundle?.alternatives.orEmpty(),
            year = bundle?.year,
        )
    }
}
