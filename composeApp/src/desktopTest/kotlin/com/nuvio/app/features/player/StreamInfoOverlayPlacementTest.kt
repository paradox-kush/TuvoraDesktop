package com.nuvio.app.features.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * B105 (iPhone simulator, landscape, controls shown): the stream-info readout
 * ("360p · H.264" / "1.7 Mbps · 25 fps" / "Mono · AAC") was anchored to the same top-end slot as
 * the player's Lock + Back buttons and drawn over them, so the only way out of the player
 * mid-playback sat inside the readout. These pin both layouts: the readout never overlaps the
 * top-bar controls, and Back still answers a tap while the readout is on screen.
 *
 * Desktop: the shipped player chrome is the native controls WebView (player-ui/controls.css),
 * which had the same overlap and is fixed there; this guards the shared Compose twin.
 */
class StreamInfoOverlayPlacementTest {
    @get:Rule
    val compose = createComposeRule()

    private val lines = listOf(
        StreamInfoLine(primary = "360p", secondary = "H.264"),
        StreamInfoLine(primary = "1.7 Mbps", secondary = "25 fps"),
        StreamInfoLine(primary = "Mono", secondary = "AAC"),
    )

    @Test
    fun readoutClearsTopBarControlsInDefaultLayout() = assertReadoutClearsTopBar(useLegacyLayout = false)

    @Test
    fun readoutClearsTopBarControlsInLegacyLayout() = assertReadoutClearsTopBar(useLegacyLayout = true)

    private fun assertReadoutClearsTopBar(useLegacyLayout: Boolean) {
        var backTaps = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NuvioTheme {
                PlayerControlsShell(
                    title = "A film",
                    streamTitle = "Stream",
                    providerName = "Provider",
                    seasonNumber = null,
                    episodeNumber = null,
                    episodeTitle = null,
                    playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, durationMs = 60_000L),
                    displayedPositionMs = 1_000L,
                    metrics = PlayerLayoutMetrics.fromWidth(874.dp),
                    resizeMode = PlayerResizeMode.entries.first(),
                    isLocked = false,
                    useLegacyLayout = useLegacyLayout,
                    streamInfoLines = lines,
                    showStreamInfo = true,
                    showPlaybackControls = true,
                    onLockToggle = {},
                    onBack = { backTaps += 1 },
                    onTogglePlayback = {},
                    onSeekBack = {},
                    onSeekForward = {},
                    onResizeModeClick = {},
                    onSpeedClick = {},
                    onSubtitleClick = {},
                    onAudioClick = {},
                    onScrubChange = {},
                    onScrubFinished = {},
                    horizontalSafePadding = 0.dp,
                )
            }
        }
        // Stagger-in is ~1.2 s; stop inside the 5 s hold so the readout is fully on screen.
        compose.mainClock.advanceTimeBy(2_000L)

        val back = compose.onNodeWithContentDescription("Close player").bounds()
        val lock = compose.onNodeWithContentDescription("Lock player controls").bounds()
        val readout = lines.flatMap { listOfNotNull(it.primary, it.secondary) }
            .map { compose.onNodeWithText(it).bounds() }
            .reduce { acc, rect -> acc.union(rect) }

        assertFalse(readout.overlaps(back), "stream info $readout overlaps Back $back")
        assertFalse(readout.overlaps(lock), "stream info $readout overlaps Lock $lock")

        compose.onNodeWithContentDescription("Close player").performClick()
        compose.mainClock.advanceTimeBy(100L)
        assertEquals(1, backTaps, "Back must answer a tap while the stream info is showing")
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    private fun Rect.union(other: Rect) = Rect(
        minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom),
    )
}
