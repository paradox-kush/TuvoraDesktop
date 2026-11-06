package com.nuvio.app.features.iptv

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B225: Settings → IPTV → a playlist's "…" menu → "Edit playlist" crashed Desktop with
 * "RootNodeOwner is already disposed". The menu item opens another settings page, which tears down the
 * page that owns the menu's popup while the popup is still handling the click.
 *
 * The crash itself is a Compose Desktop race (CanvasLayersComposeScene.doMeasureAndLayout lays out a copy of
 * its owners taken before the pass; a popup closed by a subcomposition during that pass is then laid out after
 * disposal) and does not trip deterministically in the test scene. So these tests pin its precondition: the
 * picked action must reach the screen only after the menu has left composition — never from inside the click.
 */
@OptIn(ExperimentalTestApi::class)
class PlaylistRowMenuNavigationUiTest {

    private val account = XtreamAccount(
        id = "solstice",
        name = "Solstice",
        baseUrl = "http://media:9191",
        username = "user",
        password = "pass",
    )

    private fun openMenuAndPick(itemText: String, navigatesOn: PlaylistMenuAction, keyedPages: Boolean = true) = runComposeUiTest {
        var page by mutableStateOf("list")
        val picked = mutableListOf<PlaylistMenuAction>()
        // True only while the menu item's click is being dispatched — i.e. while its popup is still composed.
        var clicking = false
        val deliveredInsideClick = mutableListOf<Boolean>()
        setContent {
            NuvioTheme {
                // The settings screen: one LazyColumn per page, keyed by page (SaveableStateProvider(page.name)).
                val holder = rememberSaveableStateHolder()
                val pages: @Composable () -> Unit = {
                    LazyColumn {
                        if (page == "list") {
                            item {
                                IptvPlaylistListPane(
                                    rows = listOf(PlaylistRowModel(account, managed = null, subtitle = "media:9191")),
                                    selectedKey = account.id,
                                    addSelected = false,
                                    pending = null,
                                    onSelect = {},
                                    onOpenDetails = {},
                                    onAdd = {},
                                    onGuideRegions = {},
                                    guideRegionsSummary = null,
                                    onMenuAction = { action, _ ->
                                        picked += action
                                        deliveredInsideClick += clicking
                                        if (action == navigatesOn) page = "form"
                                    },
                                    onConfirmDestructive = { _, _ -> },
                                    onDismissDestructive = {},
                                    onAskRemove = {},
                                    modifier = Modifier.fillParentMaxHeight(),
                                )
                            }
                        } else {
                            item {
                                var text by remember { mutableStateOf("Solstice") }
                                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Name") })
                            }
                        }
                    }
                }
                if (keyedPages) holder.SaveableStateProvider(page) { pages() } else pages()
            }
        }
        onNodeWithText("Solstice").performMouseInput { rightClick() }
        waitForIdle()
        clicking = true
        onNodeWithText(itemText).performClick()
        clicking = false
        waitForIdle()
        // Let a few more frames run: a disposed popup owner is touched on the frame after the swap.
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals(listOf(navigatesOn), picked, "the menu reports exactly the picked action")
        // The crash: the action tore down the page that owns the menu while the menu's popup was still composed,
        // so Compose Desktop could dispose that popup's RootNodeOwner mid-layout and then lay it out
        // ("RootNodeOwner is already disposed"). The action must arrive only after the menu has left composition.
        assertEquals(listOf(false), deliveredInsideClick, "the action is delivered after the menu closed, not inside its click")
        onNodeWithText("Rename").assertDoesNotExist()
        assertEquals("form", page, "the picked action navigated to the other page")
        onNodeWithText("Name").assertExists()
    }

    @Test
    fun editPlaylistFromRowMenuOpensTheFormWithoutCrashing() =
        openMenuAndPick("Edit playlist", PlaylistMenuAction.EditServerLogin)

    @Test
    fun contentFromRowMenuOpensTheContentPageWithoutCrashing() =
        openMenuAndPick("Content & categories", PlaylistMenuAction.Content)

    /** One LazyColumn whose item swaps: the page holding the menu is disposed inside the list's measure pass. */
    @Test
    fun editPlaylistWhenTheListSwapsItsItemInPlace() =
        openMenuAndPick("Edit playlist", PlaylistMenuAction.EditServerLogin, keyedPages = false)

    /** The real pages: the IPTV settings list page swapped for the real Add/Edit form page, as SettingsScreen does. */
    @Test
    fun editPlaylistFromRowMenuOnTheRealSettingsPages() = runComposeUiTest {
        var page by mutableStateOf("list")
        val state = XtreamUiState(accounts = listOf(account))
        setContent {
            NuvioTheme {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(page) {
                    LazyColumn {
                        if (page == "list") {
                            xtreamSettingsContent(
                                isTablet = true,
                                state = state,
                                onEditPlaylist = { a ->
                                    XtreamRepository.clearError()
                                    XtreamAddPage.openEdit(a.id)
                                    page = "form"
                                },
                            )
                        } else {
                            xtreamAddPlaylistContent(isTablet = true, state = state, onDone = {})
                        }
                    }
                }
            }
        }
        waitForIdle()
        onAllNodesWithText("Solstice")[0].performMouseInput { rightClick() }
        waitForIdle()
        // The detail pane has its own "Edit playlist" row; the menu's item is the one in the popup (last root).
        onAllNodesWithText("Edit playlist").onLast().performClick()
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals("form", page, "Edit playlist opened the form page")
        onNodeWithText("Save changes").assertExists()
    }
}
