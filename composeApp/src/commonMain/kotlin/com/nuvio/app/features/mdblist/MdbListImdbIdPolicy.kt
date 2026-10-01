package com.nuvio.app.features.mdblist

/**
 * Which IMDb id to ask MDBList for (MDBList ratings are keyed by IMDb id). Pure — no network.
 *
 * Port of NuvioTV `MDBListRepository.resolveImdbId`: a direct `tt…` id wins (meta id, then the
 * request's fallback id, then the add-on's `imdb_id`); otherwise a TMDB id (`tmdb:N`, `movie:N`,
 * `series:N`, or bare numeric, from the meta id then the fallback id) is converted through ONE
 * TMDB external-ids lookup, and only a `tt…` answer is accepted.
 */
internal object MdbListImdbIdPolicy {
    sealed interface Plan {
        /** An IMDb id is already known — ask MDBList directly, no lookup. */
        data class Direct(val imdbId: String) : Plan
        /** Convert this TMDB id first (one lookup), then ask MDBList. */
        data class LookupTmdb(val tmdbId: Int, val mediaType: String) : Plan
        /** Nothing MDBList can resolve. */
        data object None : Plan
    }

    private val imdbRegex = Regex("tt\\d+")
    private val tmdbPrefixes = listOf("tmdb:", "movie:", "series:")

    fun plan(metaId: String?, fallbackItemId: String?, metaImdbId: String?, mediaType: String): Plan {
        (extractImdbId(metaId) ?: extractImdbId(fallbackItemId) ?: extractImdbId(metaImdbId))
            ?.let { return Plan.Direct(it) }
        val tmdbId = extractTmdbId(metaId) ?: extractTmdbId(fallbackItemId) ?: return Plan.None
        return Plan.LookupTmdb(tmdbId, mediaType)
    }

    /** The IMDb id to use from a TMDB lookup result, or null when the lookup failed or is not `tt…`. */
    fun fromLookup(result: String?): String? = extractImdbId(result?.trim())

    fun extractImdbId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return imdbRegex.find(value)?.value
    }

    fun extractTmdbId(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        var id = value.trim()
        tmdbPrefixes.firstOrNull { id.startsWith(it, ignoreCase = true) }?.let { id = id.substring(it.length) }
        id = id.substringBefore(':').substringBefore('/').trim()
        if (id.isEmpty() || !id.all(Char::isDigit)) return null
        return id.toIntOrNull()?.takeIf { it > 0 }
    }
}
