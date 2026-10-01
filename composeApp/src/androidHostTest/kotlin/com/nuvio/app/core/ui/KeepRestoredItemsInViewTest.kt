package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

/**
 * K9 regression (wave 2, phone emulator): long-press the FIRST channel of a hub row, Hide, then Undo
 * (or unhide it in Settings) — the store said "not hidden" but the card never came back until a
 * relaunch. It was back in the data: the keyed LazyRow kept the card that had become first in view,
 * so the restored card was inserted one slot off-screen to the left. Red without
 * [KeepRestoredItemsInView] (the restored card is not displayed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeepRestoredItemsInViewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun undoing_a_hide_of_the_first_card_shows_it_again() {
        val all = listOf("Sky News 1 HD", "Sky Sports 1 HD", "Sky Movies 1 HD", "BBC One", "ITV1", "Channel 4")
        val items = mutableStateOf(all)
        compose.setContent {
            val state = rememberLazyListState()
            KeepRestoredItemsInView(state, items.value)
            LazyRow(state = state, modifier = Modifier.width(250.dp)) {
                items(items.value, key = { it }) { name -> Text(name, Modifier.size(100.dp)) }
            }
        }
        compose.onNodeWithText("Sky News 1 HD").assertIsDisplayed()

        items.value = all - "Sky News 1 HD"   // Hide
        compose.waitForIdle()
        items.value = all                     // Undo
        compose.waitForIdle()

        compose.onNodeWithText("Sky News 1 HD").assertIsDisplayed()
    }
}
