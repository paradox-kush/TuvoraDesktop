package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.app.features.iptv.XtreamProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guide's tap targets, at the level the viewer actually meets them.
 *
 * These matter more than usual because catch-up CHANGES a gesture people already use: the whole
 * programme lane was one tap target that switched channel. The rule is that only a cell with
 * somewhere else to go takes the tap, and everything else still falls through — so most of this
 * file is about what did NOT change.
 */
@OptIn(ExperimentalTestApi::class)
class LiveGuideGridUiTest {

    private val nowMs = 30L * 24 * 60 * 60 * 1000   // comfortably past epoch: degenerate-row rules bite at 0
    private val anchorMs = GuideTimeTravel.anchorForNow(nowMs)

    /**
     * One page back — where a just-finished programme is actually visible.
     *
     * The live window starts at the slot boundary BEFORE now and runs forward, which is what this
     * guide has always shown; finished programmes live to the left of it.
     */
    private val travelledAnchorMs = anchorMs - GuideTimeTravel.PAGE_MS

    private val channel = LiveGuideChannel(
        contentId = "live:account:2",
        name = "Channel Two",
        logo = null,
        streamId = 2,
        categoryId = "news",
    )
    private val archiveChannel = channel.copy(
        contentId = "live:account:3",
        name = "Archive One",
        streamId = 3,
        hasArchive = true,
    )
    private val programme = XtreamProgram(
        title = "Evening News",
        description = "",
        startMs = nowMs - 10 * 60_000L,
        endMs = nowMs + 20 * 60_000L,
        nowPlaying = true,
    )
    private val finished = XtreamProgram(
        title = "Gardeners World",
        description = "",
        startMs = nowMs - 90 * 60_000L,
        endMs = nowMs - 30 * 60_000L,
        nowPlaying = false,
    )

    private class Recorder {
        val selected = mutableListOf<LiveGuideChannel>()
        val actions = mutableListOf<Pair<XtreamProgram, ProgrammeAction>>()
        val travels = mutableListOf<Long>()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.showGuide(
        channels: List<LiveGuideChannel>,
        programmes: List<XtreamProgram>,
        recorder: Recorder,
        windowStartMs: Long = anchorMs,
    ) {
        setContent {
            NuvioTheme {
                LiveGuideGrid(
                    channels = channels,
                    currentContentId = "live:account:1",
                    nowMs = nowMs,
                    windowStartMs = windowStartMs,
                    catchUpDays = 0,
                    programmesOf = { programmes },
                    onNeedProgrammes = {},
                    onSelectChannel = recorder.selected::add,
                    onProgrammeAction = { _, programme, action -> recorder.actions.add(programme to action) },
                    onTravel = recorder.travels::add,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // --- what must NOT change --------------------------------------------------------------

    /**
     * The airing programme on a channel with NO archive: nothing to replay, so the cell stays
     * inert and the lane's tap switches channel exactly as it always has.
     */
    @Test
    fun `clicking a programme block switches to its channel`() = runComposeUiTest {
        val recorder = Recorder()
        showGuide(listOf(channel), listOf(programme), recorder)

        onNodeWithText("Evening News").performClick()

        assertEquals(listOf(channel), recorder.selected)
        assertTrue(recorder.actions.isEmpty(), "a channel with no archive must offer no programme action")
    }

    @Test
    fun `clicking the channel cell still switches channel`() = runComposeUiTest {
        val recorder = Recorder()
        showGuide(listOf(channel), listOf(programme), recorder)

        onNodeWithText("Channel Two").performClick()

        assertEquals(listOf(channel), recorder.selected)
    }

    /**
     * A FINISHED programme on a channel with no archive is gone for good. It must not take the tap
     * and must not promise anything — the lane still switches channel.
     *
     * Shown in a TRAVELLED window: the live window begins at the half-hour boundary before now, so
     * a programme that ended half an hour ago is off its left edge. Reaching the past is what the
     * travel controls are for.
     */
    @Test
    fun `a finished programme without an archive stays inert`() = runComposeUiTest {
        val recorder = Recorder()
        showGuide(listOf(channel), listOf(finished), recorder, windowStartMs = travelledAnchorMs)

        onNodeWithText("Gardeners World").performClick()

        assertEquals(listOf(channel), recorder.selected)
        assertTrue(recorder.actions.isEmpty(), "no archive means no replay affordance")
    }

    // --- what catch-up adds ------------------------------------------------------------------

    /** Finished, on an archive channel: one press replays it. No sheet — there is one destination. */
    @Test
    fun `a finished programme on an archive channel replays`() = runComposeUiTest {
        val recorder = Recorder()
        showGuide(listOf(archiveChannel), listOf(finished), recorder, windowStartMs = travelledAnchorMs)

        onNodeWithText("Gardeners World").performClick()

        assertEquals(1, recorder.actions.size)
        assertEquals(ProgrammeAction.REPLAY, recorder.actions.single().second)
        assertTrue(recorder.selected.isEmpty(), "a replay must not also switch channel")
    }

    /** Airing, on an archive channel: the one state with two answers, so it opens the sheet. */
    @Test
    fun `the airing programme on an archive channel offers start over`() = runComposeUiTest {
        val recorder = Recorder()
        showGuide(listOf(archiveChannel), listOf(programme), recorder)

        onNodeWithText("Evening News").performClick()

        assertEquals(1, recorder.actions.size)
        assertEquals(ProgrammeAction.START_OVER, recorder.actions.single().second)
        assertTrue(recorder.selected.isEmpty(), "the sheet decides — the tap must not tune the channel")
    }

    /** The channel-level signal: an archive glyph beside the name, on that channel alone. */
    @Test
    fun `an archive channel is marked in the channel column`() = runComposeUiTest {
        val recorder = Recorder()
        showGuide(listOf(archiveChannel), listOf(programme), recorder)

        onNodeWithContentDescription("This channel keeps a replay archive").assertExists()
    }

    // --- travel ------------------------------------------------------------------------------

    @Test
    fun `the earlier control travels back a page`() = runComposeUiTest {
        val recorder = Recorder()
        showGuide(listOf(archiveChannel), listOf(programme), recorder)

        onNodeWithContentDescription("Earlier").performClick()

        assertEquals(listOf(anchorMs - GuideTimeTravel.PAGE_MS), recorder.travels)
    }

    /** On the live window there is nowhere forward to go, so the control does nothing. */
    @Test
    fun `the later control is inert on the live window`() = runComposeUiTest {
        val recorder = Recorder()
        showGuide(listOf(archiveChannel), listOf(programme), recorder)

        onNodeWithContentDescription("Later").performClick()

        assertTrue(recorder.travels.isEmpty(), "the live window has nowhere later to travel")
    }

    /** Travelled away from now, the guide says so and offers one tap back. */
    @Test
    fun `a travelled window offers a way back to now`() = runComposeUiTest {
        val recorder = Recorder()
        showGuide(
            listOf(archiveChannel),
            listOf(programme),
            recorder,
            windowStartMs = anchorMs - 4 * GuideTimeTravel.PAGE_MS,
        )

        onNodeWithText("Back to now").performClick()

        assertEquals(listOf(anchorMs), recorder.travels)
    }
}
