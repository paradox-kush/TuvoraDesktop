package com.nuvio.app.features.radar

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fetches the published league catalog from the radar-fixtures edge function, so leagues can
 * be added without shipping an app release.
 *
 * Returns null on any failure — the caller keeps whatever catalog it already has, which is
 * the last good fetch or the bundled constant.
 */
internal object RadarCatalogClient {
    private val log = Logger.withTag("RadarCatalogClient")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): RadarCatalogEnvelope? = runCatching {
        val response = SupabaseProvider.client.functions.invoke(
            function = "radar-fixtures",
            body = buildJsonObject { put("catalog", "1") },
        )
        json.decodeFromString<RadarCatalogEnvelope>(response.bodyAsText())
    }.onFailure { e -> log.w(e) { "catalog fetch — FAILED" } }.getOrNull()

    /**
     * Leagues we deliberately didn't curate, for the "add a league" picker. Either browse by
     * country (optionally narrowed to one sport) or free-text search. Empty on any failure —
     * the picker shows "nothing found" rather than an error state.
     */
    suspend fun searchLeagues(country: String = "", sport: String = "", text: String = ""): List<RadarLeague> {
        if (country.isBlank() && text.isBlank()) return emptyList()
        return runCatching {
            val response = SupabaseProvider.client.functions.invoke(
                function = "radar-fixtures",
                body = buildJsonObject {
                    if (country.isNotBlank()) {
                        put("league_country", country)
                        if (sport.isNotBlank()) put("league_sport", sport)
                    } else {
                        put("league_search", text)
                    }
                },
            )
            json.decodeFromString<RadarLeagueSearchResponse>(response.bodyAsText()).leagues
        }.onFailure { e -> log.w(e) { "league search — FAILED" } }.getOrDefault(emptyList())
    }

    /**
     * Free-text club search for the "follow a team" picker. There is no browse-by-country
     * fallback here as there is for leagues: nobody scrolls to their club through a list of
     * every team in a country, they type its name.
     */
    suspend fun searchTeams(text: String): List<RadarTeam> {
        if (text.isBlank()) return emptyList()
        return runCatching {
            val response = SupabaseProvider.client.functions.invoke(
                function = "radar-fixtures",
                body = buildJsonObject { put("team_search", text) },
            )
            json.decodeFromString<RadarTeamSearchResponse>(response.bodyAsText()).teams
        }.onFailure { e -> log.w(e) { "team search — FAILED" } }.getOrDefault(emptyList())
    }
}
