package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem

internal enum class NextEpisodeSourceTier {
    SAME_PROVIDER,
    SAME_RELEASE_GROUP,
    SAME_LANGUAGE,
    ANY,
}

internal data class NextEpisodeSourceSelection(
    val stream: StreamItem,
    val tier: NextEpisodeSourceTier,
)

/**
 * Picks the stream to continue a series with, in confidence order:
 *  1. same provider (addonId) as the stream that just played,
 *  2. same bingeGroup (release chain) on any provider,
 *  3. any provider whose release is in the language currently being watched,
 *  4. nothing — the caller falls back to the manual source picker
 *     (or, when [allowAnyFallback], the first ready stream).
 */
internal object PlayerNextEpisodeSourceSelector {

    fun select(
        streams: List<StreamItem>,
        currentProviderAddonId: String?,
        currentBingeGroup: String?,
        currentLanguages: Set<String>,
        contentOriginalLanguage: String?,
        isReady: (StreamItem) -> Boolean,
        allowAnyFallback: Boolean = false,
        confidentTiersOnly: Boolean = false,
    ): NextEpisodeSourceSelection? {
        if (streams.isEmpty()) return null
        val ready = streams.filter(isReady)
        if (ready.isEmpty()) return null

        val providerId = currentProviderAddonId?.trim().orEmpty()
        val bingeGroup = currentBingeGroup?.trim().orEmpty()

        if (providerId.isNotEmpty()) {
            val sameProvider = ready.filter { it.addonId == providerId }
            if (sameProvider.isNotEmpty()) {
                val withinProvider = bingeGroup.takeIf { it.isNotEmpty() }
                    ?.let { group -> sameProvider.firstOrNull { it.behaviorHints.bingeGroup == group } }
                    ?: sameProvider.firstOrNull {
                        streamMatchesLanguage(it, currentLanguages, contentOriginalLanguage)
                    }
                    ?: sameProvider.first()
                return NextEpisodeSourceSelection(withinProvider, NextEpisodeSourceTier.SAME_PROVIDER)
            }
        }

        if (bingeGroup.isNotEmpty()) {
            ready.firstOrNull { it.behaviorHints.bingeGroup == bingeGroup }?.let { stream ->
                return NextEpisodeSourceSelection(stream, NextEpisodeSourceTier.SAME_RELEASE_GROUP)
            }
        }

        if (confidentTiersOnly) return null

        if (currentLanguages.isNotEmpty()) {
            ready.firstOrNull { streamMatchesLanguage(it, currentLanguages, contentOriginalLanguage) }
                ?.let { stream ->
                    return NextEpisodeSourceSelection(stream, NextEpisodeSourceTier.SAME_LANGUAGE)
                }
        }

        if (allowAnyFallback) {
            return NextEpisodeSourceSelection(ready.first(), NextEpisodeSourceTier.ANY)
        }
        return null
    }

    /**
     * A stream counts as "same language" when any language it advertises shares a primary
     * subtag with a target, when it is a multi-audio release, or — for releases with no
     * language markers at all — when the viewer is watching the content's original audio
     * (untagged releases almost always carry the original track).
     */
    fun streamMatchesLanguage(
        stream: StreamItem,
        targets: Set<String>,
        contentOriginalLanguage: String?,
    ): Boolean {
        if (targets.isEmpty()) return false
        val languages = detectStreamLanguages(stream)
        if (MULTI_LANGUAGE_MARKER in languages) return true
        if (languages.isEmpty()) {
            val original = contentOriginalLanguage
                ?.let { normalizeLanguageCode(it) }
                ?.substringBefore('-')
                ?: return false
            return targets.any { it.substringBefore('-') == original }
        }
        return languages.any { language ->
            val primary = language.substringBefore('-')
            targets.any { target -> target.substringBefore('-') == primary }
        }
    }

    /** Normalized (lowercase, ISO-639-1-ish) language codes a stream advertises. */
    fun detectStreamLanguages(stream: StreamItem): Set<String> {
        val parsed = stream.clientResolve?.stream?.raw?.parsed?.languages.orEmpty()
            .mapNotNull { language ->
                val normalized = normalizeLanguageCode(language)?.lowercase()
                when (normalized) {
                    null, "und", "unknown" -> null
                    "multi", "multiple" -> MULTI_LANGUAGE_MARKER
                    else -> normalized
                }
            }
        val fromText = languageCodesInText(
            listOf(
                stream.name,
                stream.title,
                stream.description,
                stream.behaviorHints.filename,
                stream.clientResolve?.filename,
                stream.clientResolve?.torrentName,
                stream.clientResolve?.stream?.raw?.filename,
                stream.clientResolve?.stream?.raw?.torrentName,
            ).filterNot { it.isNullOrBlank() }.joinToString(" "),
        )
        return (parsed + fromText).toSet()
    }
}
