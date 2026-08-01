package com.nuvio.app.features.player

data class ExternalPlayerApp(
    val id: String,
    val name: String,
)

data class SubtitleInput(
    val url: String,
    val name: String,
    val lang: String,
)

data class ExternalPlayerPlaybackRequest(
    val sourceUrl: String,
    val title: String,
    val streamTitle: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val resumePositionMs: Long = 0L,
    val subtitles: List<SubtitleInput>? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    // JSON array of intro/outro skip segments, passed to players that support auto-skipping.
    val skipSegmentsJson: String? = null,
) {
    /**
     * Builds a display title for external players.
     * For series: "Show Name - S02E05" or "Show Name - S02E05 - Episode Title"
     * For movies: just the content name (title).
     */
    fun buildPlayerTitle(includeEpisodeTitle: Boolean = false): String {
        if (season == null || episode == null) return title
        val seasonEp = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        return if (includeEpisodeTitle && !episodeTitle.isNullOrBlank()) {
            "$title - $seasonEp - $episodeTitle"
        } else {
            "$title - $seasonEp"
        }
    }
}

enum class ExternalPlayerOpenResult {
    Opened,
    NotConfigured,
    NoPlayerAvailable,
    Failed,
}

sealed interface ExternalPlayerIntentResult {
    data class Success(val intent: Any) : ExternalPlayerIntentResult
    data object NotConfigured : ExternalPlayerIntentResult
    data object Failed : ExternalPlayerIntentResult
}

/**
 * Resolves a stored external-player id against the players this device can actually launch.
 *
 * The id is platform-specific — Android's system-chooser id means nothing to iOS's URL-scheme
 * players and vice versa — but it syncs across devices along with the rest of the playback
 * settings. Without this, turning External on for one platform leaves another platform with an
 * id it cannot honour, and playback dead-ends instead of falling back to the internal player.
 *
 * Returns null when this device has no external player to hand off to, which is always the case
 * for the iOS build running on macOS: none of the iOS player URL schemes resolve there.
 */
internal fun resolveAvailableExternalPlayerId(storedId: String?): String? {
    val available = ExternalPlayerPlatform.availablePlayers()
    if (available.isEmpty()) return null
    return available.firstOrNull { it.id == storedId }?.id
        ?: available.firstOrNull { it.id == ExternalPlayerPlatform.defaultPlayerId() }?.id
        ?: available.singleOrNull()?.id
}

internal expect object ExternalPlayerPlatform {
    fun defaultPlayerId(): String?
    fun availablePlayers(): List<ExternalPlayerApp>
    fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult
    fun buildIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerIntentResult
}
