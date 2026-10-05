package com.nuvio.app.features.player

/** Subtitle-rail key for embedded closed captions that carry no real language (P3). */
internal const val SubtitleClosedCaptionsLanguageKey = "__cc__"

/**
 * P3 (W2 device pass): embedded CEA-608/708 captions (common on live IPTV) reach the subtitle
 * picker named after their codec or MIME type — libmpv "eia_608" (with language "eia-608"),
 * ExoPlayer "Unknown (application/cea-608)", the iOS bridge "Subtitle 1 (eia_608)" — and with no
 * language, so the rail filed them under "Unknown (1)". This is the one place that recognises a
 * caption track; the picker shows it as "Closed captions". A caption track that does declare a
 * real language stays under that language.
 */
internal object ClosedCaptionTracks {

    private val MARKERS = listOf(
        "eia_608", "eia-608", "eia608", "eia_708", "eia-708", "eia708",
        "cea_608", "cea-608", "cea608", "cea_708", "cea-708", "cea708",
        "closed caption",
    )

    /** True when any of the track's own strings (label, language, id, codec, MIME) names a caption format. */
    fun isClosedCaption(vararg hints: String?): Boolean =
        hints.any { hint -> hint != null && MARKERS.any { hint.contains(it, ignoreCase = true) } }

    fun isClosedCaption(track: SubtitleTrack): Boolean = isClosedCaption(track.label, track.language, track.id)

    /** The track's language once a caption-format "language" (libmpv's "eia-608") is set aside. */
    fun realLanguage(track: SubtitleTrack): String? =
        track.language?.takeUnless { it.isBlank() || isClosedCaption(it) }
}
