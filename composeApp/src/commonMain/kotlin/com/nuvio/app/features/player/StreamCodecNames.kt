package com.nuvio.app.features.player

/**
 * Display labels for codecs, shared by every mpv-backed engine (Android libmpv, iOS, and
 * the desktop native bridge) so the same file reads identically wherever it is played.
 *
 * mpv reports libavcodec short names ("hevc", "eac3"); ExoPlayer reports MIME types and
 * maps them to its own labels. This table targets the ExoPlayer spelling so the two
 * engines agree.
 */
internal object StreamCodecNames {

    private val DISPLAY_NAMES = mapOf(
        // Video
        "h264" to "H.264",
        "avc1" to "H.264",
        "hevc" to "HEVC",
        "h265" to "HEVC",
        "av1" to "AV1",
        "vp8" to "VP8",
        "vp9" to "VP9",
        "mpeg1video" to "MPEG-1",
        "mpeg2video" to "MPEG-2",
        "mpeg4" to "MPEG-4",
        "vc1" to "VC-1",
        "theora" to "Theora",
        // Audio
        "aac" to "AAC",
        "ac3" to "AC-3",
        "eac3" to "E-AC-3",
        "ac4" to "AC-4",
        "truehd" to "TrueHD",
        "dts" to "DTS",
        "dtshd" to "DTS-HD",
        "mp3" to "MP3",
        "mp2" to "MP2",
        "opus" to "Opus",
        "vorbis" to "Vorbis",
        "flac" to "FLAC",
        "alac" to "ALAC",
        "pcm_s16le" to "PCM",
        "pcm_s24le" to "PCM",
        // ExoPlayer MIME subtypes (Android ExoPlayer path: text after '/', "x-" stripped) and RFC 6381
        // codec prefixes, mapped onto the same labels. Bare "mpeg" is left alone on purpose: it is MP3
        // for audio/mpeg but MPEG-1 for video/mpeg.
        "avc" to "H.264",
        "av01" to "AV1",
        "vnd.on2.vp8" to "VP8",
        "vnd.on2.vp9" to "VP9",
        "mp4a-latm" to "AAC",
        "mp4a" to "AAC",
        "vnd.dts" to "DTS",
        "vnd.dts.hd" to "DTS-HD",
        "true-hd" to "TrueHD",
        "mpeg-l2" to "MP2",
        "raw" to "PCM",
    )

    /**
     * Unknown codecs are upper-cased rather than dropped — a raw "PRORES" still tells the
     * user more than a blank row. Idempotent, so a label that has already been mapped
     * survives a second pass unchanged.
     */
    fun display(codec: String?): String? {
        val normalized = codec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return DISPLAY_NAMES[normalized] ?: normalized.uppercase()
    }
}
