package com.nuvio.app.features.iptv

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.contracts.IptvSettingsSection
import com.nuvio.app.core.contracts.IptvSettingsState
import com.nuvio.app.features.settings.SettingsPage
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_iptv_edit_playlist
import org.jetbrains.compose.resources.stringResource

/**
 * UX19 — "Edit Playlist" for the reused Add-Playlist page while editing. [isEditing] is read on every
 * call, never captured: navigation asks right after the click handler switched the page to edit mode.
 */
internal fun playlistFormTitleOverride(editTitle: String, isEditing: () -> Boolean): (SettingsPage) -> String? =
    { page -> if (page == SettingsPage.IptvAddPlaylist && isEditing()) editTitle else null }

/**
 * B103 — the IPTV settings pages' navigation intents, pulled out of [IptvSettingsSectionImpl.renderPage]
 * so the decision is testable without Compose. Opening a page goes FORWARD ([open]); finishing the
 * Add/Edit form and the process-death bounce-backs go BACK ([back]) — exactly one pop to the parent.
 * On the iPhone each settings page is a real nav-stack entry, so a forward "open the list" after a save
 * stacked list → submitted form → list, and Back from the list returned to the submitted form.
 */
internal class IptvSettingsNavigation(
    private val open: (SettingsPage) -> Unit,
    private val back: () -> Unit,
) {
    fun openPlaylistForm() = open(SettingsPage.IptvAddPlaylist)
    fun openContent() = open(SettingsPage.IptvContent)
    fun openCategoryChecklist() = open(SettingsPage.IptvCategoryChecklist)

    /** A successful Add/Edit save returns to the playlist list the form was opened from. */
    fun playlistFormDone() = back()

    /** Process-death restore lost the page's plain-var target: leave the page. */
    fun bounceBackAfterRestore() = back()
}

/** Opaque carrier so shared settings code never names [XtreamUiState]. */
private class IptvSettingsStateHandle(val xtream: XtreamUiState) : IptvSettingsState

/**
 * The IPTV pages of the settings screen, moved off the shared SettingsScreen (firewall). Reproduces
 * exactly the four `when(page)` cases that lived inline in both the phone and tablet layouts —
 * playlist list, add/edit, content settings, category checklist — including the process-death
 * bounce-back guards. Registered by FeatureWiring; shared code reaches it via IptvSettingsSection.
 */
internal object IptvSettingsSectionImpl : IptvSettingsSection {
    @Composable
    override fun rememberState(): IptvSettingsState {
        val state by remember {
            XtreamRepository.ensureLoaded()
            XtreamRepository.uiState
        }.collectAsStateWithLifecycle()
        return IptvSettingsStateHandle(state)
    }

    @Composable
    override fun headerTitleOrNull(page: SettingsPage): String? = rememberNavigationTitleOverride()(page)

    @Composable
    override fun rememberNavigationTitleOverride(): (SettingsPage) -> String? =
        playlistFormTitleOverride(
            editTitle = stringResource(Res.string.compose_settings_page_iptv_edit_playlist),
            isEditing = { XtreamAddPage.isEdit },
        )

    override fun LazyListScope.renderPage(
        page: SettingsPage,
        isTablet: Boolean,
        state: IptvSettingsState,
        onPageChange: (SettingsPage) -> Unit,
        onNavigateBack: () -> Unit,
    ): Boolean {
        val xtreamState = (state as IptvSettingsStateHandle).xtream
        val navigation = IptvSettingsNavigation(open = onPageChange, back = onNavigateBack)
        when (page) {
            SettingsPage.Iptv -> xtreamSettingsContent(
                isTablet = isTablet,
                state = xtreamState,
                onAddManual = { type ->
                    XtreamRepository.clearError()
                    XtreamAddPage.openAdd(type)
                    navigation.openPlaylistForm()
                },
                onEditPlaylist = { account ->
                    XtreamRepository.clearError()
                    XtreamAddPage.openEdit(account.id)
                    navigation.openPlaylistForm()
                },
                onOpenContent = { account ->
                    XtreamContentPage.open(account.id)
                    navigation.openContent()
                },
            )
            SettingsPage.IptvAddPlaylist -> xtreamAddPlaylistContent(
                isTablet = isTablet,
                state = xtreamState,
                onDone = navigation::playlistFormDone,
            )
            SettingsPage.IptvContent -> if (XtreamContentPage.accountId == null) {
                // Process-death restore: the page survives (rememberSaveable) but the
                // target playlist id is a plain var — bounce back to the playlist list.
                item { LaunchedEffect(Unit) { navigation.bounceBackAfterRestore() } }
            } else {
                xtreamContentSettingsContent(
                    isTablet = isTablet,
                    state = xtreamState,
                    onOpenType = { type ->
                        XtreamContentPage.openChecklist(type)
                        navigation.openCategoryChecklist()
                    },
                )
            }
            SettingsPage.IptvCategoryChecklist -> if (
                XtreamContentPage.accountId == null || XtreamContentPage.type == null
            ) {
                // Process-death restore: the drilled-into type is a plain var — bounce back
                // (pops to the content page, whose own guard then pops to the list).
                item { LaunchedEffect(Unit) { navigation.bounceBackAfterRestore() } }
            } else {
                xtreamCategoryChecklistContent(
                    isTablet = isTablet,
                    state = xtreamState,
                )
            }
            else -> return false
        }
        return true
    }
}
