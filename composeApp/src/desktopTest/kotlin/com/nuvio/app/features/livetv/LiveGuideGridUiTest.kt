package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.iptv.XtreamProgram
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LiveGuideGridUiTest {

    private val nowMs = 1_800_000L
    private val channel = LiveGuideChannel(
        contentId = "live:account:2",
        name = "Channel Two",
        logo = null,
        streamId = 2,
        categoryId = "news",
    )
    private val programme = XtreamProgram(
        title = "Evening News",
        description = "",
        startMs = nowMs - 10 * 60_000L,
        endMs = nowMs + 20 * 60_000L,
        nowPlaying = true,
    )

    @Test
    fun `clicking a programme block switches to its channel`() = runComposeUiTest {
        val selected = mutableListOf<LiveGuideChannel>()
        setContent {
            NuvioTheme {
                LiveGuideGrid(
                    channels = listOf(channel),
                    currentContentId = "live:account:1",
                    nowMs = nowMs,
                    programmesOf = { listOf(programme) },
                    onNeedProgrammes = {},
                    onSelectChannel = selected::add,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("Evening News").performClick()

        assertEquals(listOf(channel), selected)
    }

    @Test
    fun `clicking the channel cell still switches channel`() = runComposeUiTest {
        val selected = mutableListOf<LiveGuideChannel>()
        setContent {
            NuvioTheme {
                LiveGuideGrid(
                    channels = listOf(channel),
                    currentContentId = "live:account:1",
                    nowMs = nowMs,
                    programmesOf = { listOf(programme) },
                    onNeedProgrammes = {},
                    onSelectChannel = selected::add,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("Channel Two").performClick()

        assertEquals(listOf(channel), selected)
    }
}
