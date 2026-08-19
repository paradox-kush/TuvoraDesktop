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
    override fun headerTitleOrNull(page: SettingsPage): String? =
        if (page == SettingsPage.IptvAddPlaylist && XtreamAddPage.isEdit) {
            stringResource(Res.string.compose_settings_page_iptv_edit_playlist)
        } else {
            null
        }

    override fun LazyListScope.renderPage(
        page: SettingsPage,
        isTablet: Boolean,
        state: IptvSettingsState,
        onPageChange: (SettingsPage) -> Unit,
    ): Boolean {
        val xtreamState = (state as IptvSettingsStateHandle).xtream
        when (page) {
            SettingsPage.Iptv -> xtreamSettingsContent(
                isTablet = isTablet,
                state = xtreamState,
                onAddPlaylist = {
                    XtreamRepository.clearError()
                    XtreamAddPage.openAdd()
                    onPageChange(SettingsPage.IptvAddPlaylist)
                },
                onEditPlaylist = { account ->
                    XtreamRepository.clearError()
                    XtreamAddPage.openEdit(account.id)
                    onPageChange(SettingsPage.IptvAddPlaylist)
                },
                onOpenContent = { account ->
                    XtreamContentPage.open(account.id)
                    onPageChange(SettingsPage.IptvContent)
                },
            )
            SettingsPage.IptvAddPlaylist -> xtreamAddPlaylistContent(
                isTablet = isTablet,
                state = xtreamState,
                onDone = { onPageChange(SettingsPage.Iptv) },
            )
            SettingsPage.IptvContent -> if (XtreamContentPage.accountId == null) {
                // Process-death restore: the page survives (rememberSaveable) but the
                // target playlist id is a plain var — bounce back to the playlist list.
                item { LaunchedEffect(Unit) { onPageChange(SettingsPage.Iptv) } }
            } else {
                xtreamContentSettingsContent(
                    isTablet = isTablet,
                    state = xtreamState,
                    onOpenType = { type ->
                        XtreamContentPage.openChecklist(type)
                        onPageChange(SettingsPage.IptvCategoryChecklist)
                    },
                )
            }
            SettingsPage.IptvCategoryChecklist -> if (
                XtreamContentPage.accountId == null || XtreamContentPage.type == null
            ) {
                // Process-death restore: the drilled-into type is a plain var — bounce back.
                item { LaunchedEffect(Unit) { onPageChange(SettingsPage.Iptv) } }
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
