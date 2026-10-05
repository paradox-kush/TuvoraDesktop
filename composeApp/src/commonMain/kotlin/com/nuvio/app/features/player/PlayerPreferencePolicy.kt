package com.nuvio.app.features.player

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Player preferences (community plan G7: F37 remember, F36 manual zoom, F47/UX61 subtitle style).
 *
 * One shared, pure model that every engine maps from. Persistence and the engine calls are per
 * platform; the decisions below are not, so they are identical on Android, iOS, desktop and Apple TV
 * (tvosCore compiles this file) and are hand-ported to NuvioTV under the same names.
 *
 * Public surface (kept small on purpose — the F28 live overlay integrates with it):
 *  - [VideoZoom] + [VideoZoomPolicy]: manual zoom on top of the resize mode, and how each engine
 *    family expresses it (mpv properties, or a View/layer transform for ExoPlayer).
 *  - [PlayerPreferencePolicy]: what a new play starts with (per-series memory over global defaults)
 *    and what an in-player change writes back.
 *  - [SubtitleStyleMpvMapping]: the subtitle box / outline / side-padding properties for libmpv.
 */

/**
 * Manual zoom (F36) layered on top of [PlayerResizeMode].
 *
 * [scaleX]/[scaleY] multiply the picture the resize mode produced (1 = unchanged). [panX]/[panY] move
 * it by a fraction of the *scaled* picture, which is exactly mpv's `video-pan-x/y` unit, so the
 * same value means the same thing on every engine.
 */
data class VideoZoom(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val isIdentity: Boolean
        get() = VideoZoomPolicy.normalize(this) == IDENTITY

    companion object {
        val IDENTITY = VideoZoom()
    }
}

enum class VideoZoomAxis { Both, Width, Height, PanX, PanY }

/** How a View-based surface (ExoPlayer's SurfaceView, TV's scaled frames) expresses a [VideoZoom]. */
data class SurfaceZoomTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
)

object VideoZoomPolicy {
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 3f
    const val SCALE_STEP = 0.05f
    const val MAX_PAN = 0.5f
    const val PAN_STEP = 0.02f

    /** Clamped and rounded to 2 decimals, so stepping and storage never accumulate float drift. */
    fun normalize(zoom: VideoZoom): VideoZoom = VideoZoom(
        scaleX = round2(zoom.scaleX.finiteOr(1f).coerceIn(MIN_SCALE, MAX_SCALE)),
        scaleY = round2(zoom.scaleY.finiteOr(1f).coerceIn(MIN_SCALE, MAX_SCALE)),
        panX = round2(zoom.panX.finiteOr(0f).coerceIn(-MAX_PAN, MAX_PAN)),
        panY = round2(zoom.panY.finiteOr(0f).coerceIn(-MAX_PAN, MAX_PAN)),
    )

    /** One remote/button press: [steps] is +1 / -1 (or more for a held key). */
    fun adjust(zoom: VideoZoom, axis: VideoZoomAxis, steps: Int): VideoZoom {
        val scaleDelta = SCALE_STEP * steps
        val panDelta = PAN_STEP * steps
        val next = when (axis) {
            VideoZoomAxis.Both -> zoom.copy(scaleX = zoom.scaleX + scaleDelta, scaleY = zoom.scaleY + scaleDelta)
            VideoZoomAxis.Width -> zoom.copy(scaleX = zoom.scaleX + scaleDelta)
            VideoZoomAxis.Height -> zoom.copy(scaleY = zoom.scaleY + scaleDelta)
            VideoZoomAxis.PanX -> zoom.copy(panX = zoom.panX + panDelta)
            VideoZoomAxis.PanY -> zoom.copy(panY = zoom.panY + panDelta)
        }
        return normalize(next)
    }

    /**
     * libmpv (Android, iOS, desktop, Apple TV): `video-scale-x/y` multiply the display size on top of
     * `panscan`/`video-zoom`, `video-pan-x/y` move it. Always the full set, so a reset really resets.
     * All four exist in every mpv we bundle (0.38 desktop macOS through 0.41 iOS).
     */
    fun mpvProperties(zoom: VideoZoom): List<Pair<String, String>> {
        val z = normalize(zoom)
        return listOf(
            "video-scale-x" to z.scaleX.mpvNumber(),
            "video-scale-y" to z.scaleY.mpvNumber(),
            "video-pan-x" to z.panX.mpvNumber(),
            "video-pan-y" to z.panY.mpvNumber(),
        )
    }

    /**
     * A View/layer transform for engines that draw into a SurfaceView (ExoPlayer). Since Android N a
     * SurfaceView honours scale/translation of its view (AOSP SurfaceView class docs), so this needs no
     * TextureView. [baseScaleX]/[baseScaleY] are a scale the host already applies for its own aspect
     * mode (NuvioTV), multiplied in so the pan stays a fraction of what is on screen.
     */
    fun surfaceTransform(
        zoom: VideoZoom,
        widthPx: Int,
        heightPx: Int,
        baseScaleX: Float = 1f,
        baseScaleY: Float = 1f,
    ): SurfaceZoomTransform {
        val z = normalize(zoom)
        val sx = baseScaleX * z.scaleX
        val sy = baseScaleY * z.scaleY
        return SurfaceZoomTransform(
            scaleX = sx,
            scaleY = sy,
            translationX = z.panX * widthPx.coerceAtLeast(0) * sx,
            translationY = z.panY * heightPx.coerceAtLeast(0) * sy,
        )
    }

    /** "120% × 100%" for the panel title; position is shown separately. */
    fun scaleLabel(zoom: VideoZoom): String {
        val z = normalize(zoom)
        val w = (z.scaleX * 100).roundToInt()
        val h = (z.scaleY * 100).roundToInt()
        return if (w == h) "$w%" else "$w% × $h%"
    }

    private fun Float.finiteOr(fallback: Float): Float = if (isNaN() || isInfinite()) fallback else this
    private fun round2(value: Float): Float = (value * 100f).roundToInt() / 100f

    /** Locale-free number for mpv (never "1,05"). */
    private fun Float.mpvNumber(): String {
        val hundredths = (this * 100f).roundToInt()
        val sign = if (hundredths < 0) "-" else ""
        val magnitude = abs(hundredths)
        return "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}"
    }
}

/** What a play starts with for the picture: the resize mode plus any manual zoom. */
data class PictureChoice(
    val resizeMode: PlayerResizeMode,
    val zoom: VideoZoom,
)

/**
 * "Remember my player preferences" (F37).
 *
 * Scope: per series (the player's parent meta id — a Stremio series id, an Xtream series or VOD id),
 * stored next to the per-series track memory that already existed. Fallback: the global settings —
 * preferred audio/subtitle languages for tracks, the last-used resize mode for the picture, and no
 * manual zoom (zoom is content-specific: a 4:3 anime fix must not follow you into a film).
 *
 * The toggle only governs the per-series memory. The global last-used resize mode predates it and
 * keeps working either way, so turning the toggle off never makes the player forget more than it did
 * before the toggle existed.
 */
object PlayerPreferencePolicy {
    const val DEFAULT_REMEMBER = true

    /** The per-series memory to restore from, or null to fall back to the global settings. */
    fun seriesMemory(
        rememberEnabled: Boolean,
        stored: PersistedPlayerTrackPreference?,
    ): PersistedPlayerTrackPreference? = if (rememberEnabled) stored else null

    /** Whether an in-player choice (track, aspect, zoom) is written to the per-series memory. */
    fun persistsSeriesChoice(rememberEnabled: Boolean, seriesKey: String?): Boolean =
        rememberEnabled && !seriesKey.isNullOrBlank()

    fun initialPicture(
        rememberEnabled: Boolean,
        stored: PersistedPlayerTrackPreference?,
        globalResizeMode: PlayerResizeMode,
    ): PictureChoice {
        val memory = seriesMemory(rememberEnabled, stored)
        val mode = memory?.resizeMode
            ?.let { name -> PlayerResizeMode.entries.firstOrNull { it.name == name } }
            ?: globalResizeMode
        return PictureChoice(resizeMode = mode, zoom = memory?.storedZoom() ?: VideoZoom.IDENTITY)
    }

    /** The memory after an in-player picture change. Identity zoom is stored as "no zoom". */
    fun withPicture(
        current: PersistedPlayerTrackPreference,
        resizeMode: PlayerResizeMode,
        zoom: VideoZoom,
    ): PersistedPlayerTrackPreference {
        val z = VideoZoomPolicy.normalize(zoom)
        val identity = z == VideoZoom.IDENTITY
        return current.copy(
            resizeMode = resizeMode.name,
            zoomScaleX = if (identity) null else z.scaleX,
            zoomScaleY = if (identity) null else z.scaleY,
            zoomPanX = if (identity) null else z.panX,
            zoomPanY = if (identity) null else z.panY,
        )
    }

    private fun PersistedPlayerTrackPreference.storedZoom(): VideoZoom? {
        if (zoomScaleX == null && zoomScaleY == null && zoomPanX == null && zoomPanY == null) return null
        return VideoZoomPolicy.normalize(
            VideoZoom(
                scaleX = zoomScaleX ?: 1f,
                scaleY = zoomScaleY ?: 1f,
                panX = zoomPanX ?: 0f,
                panY = zoomPanY ?: 0f,
            ),
        )
    }
}

/**
 * Subtitle box, outline and side padding for libmpv (F47 + UX61).
 *
 * UX61 root cause: every mpv path chose `sub-border-style=opaque-box` for any non-transparent
 * background. In libass that is BorderStyle 3, whose box is painted in the OUTLINE colour (opaque
 * black by default) and padded by the outline size; `sub-back-color` only tints a shadow box that a
 * zero shadow offset hides. So the "dim" preset rendered as a near-solid box. And with the default
 * outline enabled, Android chose `outline-and-shadow`, which draws no background at all.
 *
 * `background-box` (libass BorderStyle 4) paints one box in `sub-back-color` with its alpha honoured,
 * padded by `sub-shadow-offset`, and keeps the outline behaving as usual (mpv manual,
 * `--sub-border-style`). The `sub-border-*` names are used for the outline because they are accepted
 * by mpv 0.38 (desktop macOS, where `sub-outline-*` and `sub-border-style` do not exist yet and a
 * non-zero back-colour alpha already selects BorderStyle 4) and are aliases in 0.39+.
 */
object SubtitleStyleMpvMapping {
    /** Box padding around the text, in mpv scaled pixels (720-line reference). Soft, not cramped. */
    const val BOX_PADDING = 4.0

    /** mpv's own default `sub-margin-x` (scaled pixels). */
    const val MPV_DEFAULT_MARGIN_X = 19.0

    /** Scaled-pixel width of a 16:9 picture at mpv's 720-line reference height. */
    private const val REFERENCE_WIDTH_16_9 = 1280.0

    fun borderStyle(backgroundAlpha: Float): String =
        if (backgroundAlpha > 0f) "background-box" else "outline-and-shadow"

    /**
     * `sub-margin-x` for a side padding of [sideMarginPercent] of the picture width on each side.
     * Never below mpv's default, so 0% means "as before".
     */
    fun marginX(sideMarginPercent: Int, referenceWidth: Double = REFERENCE_WIDTH_16_9): Double {
        val percent = sideMarginPercent.coerceIn(0, SubtitleSideMargin.MAX_PERCENT)
        return maxOf(MPV_DEFAULT_MARGIN_X, referenceWidth * percent / 100.0)
    }

    /**
     * The box/outline/margin properties. Colours are mpv `#AARRGGBB` strings (what
     * [toStorageHexString] already produces). Platforms keep their own font-size / position scale.
     */
    fun properties(
        backgroundColorHex: String,
        backgroundAlpha: Float,
        outlineColorHex: String,
        outlineSize: Double,
        sideMarginPercent: Int,
    ): List<Pair<String, String>> {
        val box = backgroundAlpha > 0f
        return listOf(
            "sub-back-color" to backgroundColorHex,
            "sub-border-color" to outlineColorHex,
            "sub-border-size" to outlineSize.coerceAtLeast(0.0).mpvNumber(),
            "sub-border-style" to borderStyle(backgroundAlpha),
            "sub-shadow-offset" to (if (box) BOX_PADDING else 0.0).mpvNumber(),
            "sub-margin-x" to marginX(sideMarginPercent).mpvNumber(),
        )
    }

    private fun Double.mpvNumber(): String {
        val tenths = kotlin.math.round(this * 10.0).toLong()
        return "${tenths / 10}.${abs(tenths % 10)}"
    }
}

/** Horizontal subtitle padding (F47): percent of the picture width kept clear on each side. */
object SubtitleSideMargin {
    const val DEFAULT_PERCENT = 5
    const val MAX_PERCENT = 20

    /** Pixels of padding on each side for a View-based renderer (ExoPlayer's SubtitleView). */
    fun paddingPx(widthPx: Int, sideMarginPercent: Int): Int =
        (widthPx.coerceAtLeast(0) * sideMarginPercent.coerceIn(0, MAX_PERCENT) / 100f).roundToInt()
}
