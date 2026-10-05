package com.nuvio.app.features.livetv

import com.nuvio.app.features.player.PersistedPlayerTrackPreference
import com.nuvio.app.features.player.PictureChoice
import com.nuvio.app.features.player.PlayerPreferencePolicy
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.next

/**
 * F28 on phone/tablet (+ B123): the decisions of the fullscreen Live TV controls, which are now the
 * regular player's own controls (PlayerControlsShell in live mode) with the live keep/drop list.
 *
 * Kept: play/pause, channel up/down (in place of the seek buttons), subtitles, audio, aspect + manual
 * zoom, stream info, retry only on an error. Dropped: seek bar, ±10 s, speed, sources, episodes.
 * The picture uses lane F's model (PlayerPreferencePolicy) keyed by CHANNEL: a channel keeps its
 * own aspect/zoom, and live never rewrites the global aspect (a crop picked for one 4:3 channel
 * must not crop films). Same button vectors as TV's LiveControlsPolicy.
 */
internal object LiveFullscreenControlsPolicy {

    data class Buttons(
        val retry: Boolean,
        val subtitles: Boolean,
        val audio: Boolean,
        val channelZap: Boolean,
    )

    fun buttons(failed: Boolean, subtitleTracks: Int, audioTracks: Int, channelCount: Int): Buttons = Buttons(
        retry = failed,
        subtitles = subtitleTracks > 0,
        audio = audioTracks > 1,
        channelZap = channelCount > 1,
    )

    /** The channel [delta] steps from [currentId], wrapping at both ends; null when there is none. */
    fun neighbour(channelIds: List<String>, currentId: String, delta: Int): String? {
        if (channelIds.size < 2) return null
        val index = channelIds.indexOf(currentId)
        if (index < 0) return channelIds.first()
        val size = channelIds.size
        return channelIds[((index + delta) % size + size) % size]
    }

    fun initialPicture(
        rememberEnabled: Boolean,
        stored: PersistedPlayerTrackPreference?,
        globalResizeMode: PlayerResizeMode,
    ): PictureChoice = PlayerPreferencePolicy.initialPicture(rememberEnabled, stored, globalResizeMode)

    /** The regular player's own cycle on this platform (desktop: Fit, Zoom, Stretch). */
    fun nextAspect(choice: PictureChoice): PictureChoice = choice.copy(resizeMode = choice.resizeMode.next())
}
