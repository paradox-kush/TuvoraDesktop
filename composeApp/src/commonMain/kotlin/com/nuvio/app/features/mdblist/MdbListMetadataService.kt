package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.tmdb.TmdbService

object MdbListMetadataService {
    const val PROVIDER_IMDB = "imdb"
    const val PROVIDER_TMDB = "tmdb"
    const val PROVIDER_TOMATOES = "tomatoes"
    const val PROVIDER_METACRITIC = "metacritic"
    const val PROVIDER_TRAKT = "trakt"
    const val PROVIDER_LETTERBOXD = "letterboxd"
    const val PROVIDER_AUDIENCE = "audience"
    const val PROVIDER_MAL = "mal"

    val PROVIDER_PRIORITY_ORDER = listOf(
        PROVIDER_IMDB,
        PROVIDER_TMDB,
        PROVIDER_TOMATOES,
        PROVIDER_METACRITIC,
        PROVIDER_TRAKT,
        PROVIDER_LETTERBOXD,
        PROVIDER_AUDIENCE,
        PROVIDER_MAL,
    )

    private val ratingsRepository = lazy { MdbListRatingsRepository(MdbListTracker.ratings) }
    private val repository by ratingsRepository

    fun shouldFetchForMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: MdbListSettings,
    ): Boolean {
        if (!settings.isActive) return false
        if (settings.enabledProvidersInPriorityOrder().isEmpty()) return false
        return planFor(meta, fallbackItemId) != MdbListImdbIdPolicy.Plan.None
    }

    suspend fun enrichMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: MdbListSettings,
    ): MetaDetails = enrichMeta(
        meta = meta,
        fallbackItemId = fallbackItemId,
        settings = settings,
        // Cached in TmdbService (both directions); falls back to the built-in TMDB key like NuvioTV.
        tmdbToImdb = TmdbService::tmdbToImdb,
        fetchRatings = { imdbId, mediaType, credential, providers ->
            repository.getRatings(
                imdbId = imdbId,
                mediaType = mediaType,
                credential = credential,
                providers = providers,
            )
        },
    )

    internal suspend fun enrichMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: MdbListSettings,
        tmdbToImdb: suspend (tmdbId: Int, mediaType: String) -> String?,
        fetchRatings: suspend (
            imdbId: String,
            mediaType: String,
            credential: MdbListRatingsCredential,
            providers: List<String>,
        ) -> List<MetaExternalRating>,
    ): MetaDetails {
        if (!shouldFetchForMeta(meta, fallbackItemId, settings)) {
            return meta.copy(externalRatings = emptyList())
        }
        val credential = settings.credential ?: return meta.copy(externalRatings = emptyList())

        val imdbId = when (val plan = planFor(meta, fallbackItemId)) {
            is MdbListImdbIdPolicy.Plan.Direct -> plan.imdbId
            is MdbListImdbIdPolicy.Plan.LookupTmdb ->
                MdbListImdbIdPolicy.fromLookup(tmdbToImdb(plan.tmdbId, plan.mediaType))
            MdbListImdbIdPolicy.Plan.None -> null
        } ?: return meta.copy(externalRatings = emptyList())

        val ratings = fetchRatings(
            imdbId,
            toMdbListMediaType(meta.type),
            credential,
            settings.enabledProvidersInPriorityOrder(),
        )

        return meta.copy(externalRatings = ratings)
    }

    fun clearCache() {
        if (ratingsRepository.isInitialized()) repository.clearCache()
    }

    private fun planFor(meta: MetaDetails, fallbackItemId: String): MdbListImdbIdPolicy.Plan =
        MdbListImdbIdPolicy.plan(
            metaId = meta.id,
            fallbackItemId = fallbackItemId,
            metaImdbId = meta.imdbId,
            mediaType = meta.type,
        )

    private fun toMdbListMediaType(metaType: String): String {
        val normalized = metaType.trim().lowercase()
        return if (normalized == "movie") "movie" else "show"
    }
}
