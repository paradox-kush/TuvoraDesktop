package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.iptv.match.XtreamTmdbResolver
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.iptv.match.indexingStatusLine
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsController
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_desktop_back_to_list
import nuvio.composeapp.generated.resources.provider_details_detach_failed
import nuvio.composeapp.generated.resources.provider_details_detached_toast
import nuvio.composeapp.generated.resources.provider_details_rematch_started
import nuvio.composeapp.generated.resources.provider_managed_row
import nuvio.composeapp.generated.resources.provider_managed_row_day
import nuvio.composeapp.generated.resources.provider_managed_row_days
import nuvio.composeapp.generated.resources.provider_managed_row_expired
import nuvio.composeapp.generated.resources.provider_setup_added_other_profile_toast
import nuvio.composeapp.generated.resources.provider_setup_added_toast
import nuvio.composeapp.generated.resources.provider_setup_already_toast
import nuvio.composeapp.generated.resources.provider_setup_nothing_added_toast
import nuvio.composeapp.generated.resources.provider_setup_skipped_login_toast
import nuvio.composeapp.generated.resources.provider_setup_skipped_url_toast
import org.jetbrains.compose.resources.stringResource

/** Below this width the two panes do not fit side by side: the list and the pane take turns. */
private const val SIDE_BY_SIDE_MIN_WIDTH_DP = 720

/**
 * Desktop IPTV settings: playlist list on the left, one pane on the right (the playlist's details, the "add a
 * playlist" page, or the setup preview). No popup, no tabs. Keyboard and mouse both complete every task; hover
 * reveals, never requires. All decisions live in the policies/controllers; this wires them to the layout.
 */
@Composable
internal fun IptvMasterDetail(
    state: XtreamUiState,
    onAddManual: (XtreamSourceType) -> Unit,
    onEditPlaylist: (XtreamAccount) -> Unit,
    onOpenContent: (XtreamAccount) -> Unit,
    onGuideRegions: () -> Unit,
    guideRegionsSummary: String?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val pane by IptvSettingsPane.state.collectAsStateWithLifecycle()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val auth by AuthRepository.state.collectAsStateWithLifecycle()
    val managedByProfile by ManagedInfoRepository.state.collectAsStateWithLifecycle()
    val knownInfo by PlaylistAccountInfoStore.shared.known.collectAsStateWithLifecycle()
    val activeServers by XtreamRepository.activeServers.collectAsStateWithLifecycle()
    val indexingAccounts by XtreamTmdbResolver.indexing.collectAsStateWithLifecycle()
    val indexProgress by XtreamMatchIndex.buildProgress.collectAsStateWithLifecycle()
    val profileId = ProfileRepository.activeProfileId
    val managed = managedByProfile[profileId] ?: ManagedInfoRepository.forProfile(profileId)

    val accounts = state.accounts
    val selectedKey = IptvSettingsPane.resolveSelection(accounts, pane.selectedKey)
    val selected = accounts.firstOrNull { it.id == selectedKey }
    // With nothing to show details for, the right pane is the add page.
    val mode = if (selected == null) PaneMode.Add else pane.mode

    val setupController = SetupCodeController.shared
    val setupUi by setupController.state.collectAsStateWithLifecycle()

    // The hidden-items dialog (kept from the old actions dialog).
    var hiddenFor by remember { mutableStateOf<XtreamAccount?>(null) }
    val hiddenController = remember(scope) { IptvHiddenItemsController(scope) }
    val hiddenState by hiddenController.state.collectAsStateWithLifecycle()

    val detailsController = remember { PlaylistDetailsController() }
    val liveState by detailsController.live.collectAsStateWithLifecycle()
    // The wait ends at the check's own deadline even if the panel never answers (BoundedLoad).
    val liveStatus = rememberEffectiveLoadStatus(liveState.load)
    val live = if (liveStatus != liveState.load) liveState.copy(load = liveStatus) else liveState
    LaunchedEffect(selected?.id) { selected?.let { detailsController.load(it) } }
    // "N days left" on managed rows: one panel question per managed playlist per freshness window.
    LaunchedEffect(managed.keys, accounts) {
        accounts.filter { it.id in managed }.forEach { PlaylistAccountInfoStore.shared.infoFor(it) }
    }

    // A finished redeem: say what happened, then land on the new playlist (or the list when it is another profile's).
    val completion = setupUi.completed
    if (completion != null) {
        val toast = when (completion.outcome) {
            CompletionKind.ALREADY_IN_ACCOUNT -> stringResource(Res.string.provider_setup_already_toast, completion.providerName)
            CompletionKind.NOTHING_MISSING_LOGIN -> stringResource(Res.string.provider_setup_skipped_login_toast, completion.providerName)
            CompletionKind.NOTHING_INVALID_URL -> stringResource(Res.string.provider_setup_skipped_url_toast, completion.providerName)
            CompletionKind.NOTHING_ADDED -> stringResource(Res.string.provider_setup_nothing_added_toast)
            CompletionKind.ADDED ->
                if (completion.profileIndex != profileState.activeProfile?.profileIndex) {
                    stringResource(
                        Res.string.provider_setup_added_other_profile_toast, completion.providerName,
                        profileState.profiles.firstOrNull { it.profileIndex == completion.profileIndex }?.name.orEmpty(),
                    )
                } else {
                    stringResource(Res.string.provider_setup_added_toast, completion.providerName)
                }
        }
        LaunchedEffect(completion) {
            NuvioToastController.show(toast)
            val plan = SetupCompletionNavigation.plan(completion, fromAddPage = true)
            if (plan.openDetailsKey != null) IptvSettingsPane.select(plan.openDetailsKey) else IptvSettingsPane.closeAdd()
            setupController.finish()
        }
    }

    val detachedToast = managed[selectedKey]?.let { stringResource(Res.string.provider_details_detached_toast, it.providerName) }.orEmpty()
    val detachFailed = stringResource(Res.string.provider_details_detach_failed)
    val rematchStarted = stringResource(Res.string.provider_details_rematch_started)
    val nowSec = TraktPlatformClock.nowEpochMs() / 1000

    @Composable
    fun rowModel(account: XtreamAccount): PlaylistRowModel {
        val info = managed[account.id]
        val subtitle = if (info != null) {
            managedRowLine(info, knownInfo[account.id], nowSec)
        } else {
            state.saveWarnings[account.id]
                ?: indexingStatusLine(isIndexing = account.id in indexingAccounts, progress = indexProgress[account.id])
                ?: (ServerFailoverPolicy.backupLabel(activeServers[account.id] ?: 0) ?: PlaylistAddress.hostOnly(account.baseUrl).orEmpty())
        }
        return PlaylistRowModel(account, info, subtitle)
    }

    fun act(action: PlaylistMenuAction, account: XtreamAccount) {
        when (action) {
            PlaylistMenuAction.Open -> IptvSettingsPane.select(account.id)
            PlaylistMenuAction.Rename -> IptvSettingsPane.startRename(account.id)
            PlaylistMenuAction.ToggleEnabled -> XtreamRepository.setEnabled(account.id, !account.enabled)
            PlaylistMenuAction.Content -> onOpenContent(account)
            PlaylistMenuAction.Hidden -> { hiddenFor = account; hiddenController.open(account) }
            PlaylistMenuAction.Rematch -> { detailsController.rematch(account); NuvioToastController.show(rematchStarted) }
            PlaylistMenuAction.EditServerLogin -> onEditPlaylist(account)
            PlaylistMenuAction.Detach -> IptvSettingsPane.askDestructive(DestructiveAction.DETACH, account.id, PopoverOrigin.Row)
            PlaylistMenuAction.Remove -> IptvSettingsPane.askDestructive(DestructiveAction.REMOVE, account.id, PopoverOrigin.Row)
        }
    }

    fun confirm(action: DestructiveAction, account: XtreamAccount) {
        IptvSettingsPane.dismissDestructive()
        when (action) {
            DestructiveAction.REMOVE -> {
                val next = IptvSettingsPane.selectionAfterRemoval(accounts, account.id)
                XtreamRepository.remove(account.id)
                IptvSettingsPane.select(next)
            }
            DestructiveAction.DETACH ->
                // Not on this composition's scope: leaving mid-detach must not cancel the refresh + pull that follow.
                ManagedPlaylistActions.shared.detachInBackground(account.id) { ok ->
                    NuvioToastController.show(if (ok) detachedToast else detachFailed)
                }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wide = maxWidth >= SIDE_BY_SIDE_MIN_WIDTH_DP.dp
        var narrowShowsPane by remember { mutableStateOf(false) }

        val rightPane: @Composable (Modifier) -> Unit = { m ->
            when {
                mode == PaneMode.Add && pane.addStage == AddStage.Preview -> IptvSetupPreviewPane(
                    ui = setupUi, controller = setupController, profileState = profileState, auth = auth,
                    onCancelled = { IptvSettingsPane.backToEntry() }, modifier = m,
                )
                mode == PaneMode.Add -> IptvSetupCodePane(
                    ui = setupUi, controller = setupController,
                    onPreview = { IptvSettingsPane.openPreview() },
                    onManualRoute = onAddManual, modifier = m,
                )
                selected != null -> {
                    val info = managed[selected.id]
                    val model = ManagedDetailsModel.build(
                        account = selected, info = info, accountInfo = live.info, counts = live.counts, nowEpochSec = nowSec,
                        addressLine = ServerFailoverPolicy.backupLabel(activeServers[selected.id] ?: 0) ?: PlaylistAddress.hostOnly(selected.baseUrl),
                        panelCheckFailed = live.hasPanel && !live.loading && live.info == null,
                    )
                    IptvPlaylistDetailPane(
                        model = model, live = live, account = selected,
                        renaming = pane.renamingKey == selected.id,
                        pending = pane.pending?.takeIf { it.playlistKey == selected.id && it.origin == PopoverOrigin.Detail },
                        onStartRename = { IptvSettingsPane.startRename(selected.id) },
                        onRename = { name ->
                            XtreamRepository.updateOptions(selected.id) { ManagedEditPolicy.renamed(it, name) }
                            IptvSettingsPane.stopRename()
                        },
                        onCancelRename = IptvSettingsPane::stopRename,
                        onAction = { act(it, selected) },
                        onAskDestructive = { IptvSettingsPane.askDestructive(it, selected.id, PopoverOrigin.Detail) },
                        onConfirmDestructive = { confirm(it, selected) },
                        onDismissDestructive = IptvSettingsPane::dismissDestructive,
                        modifier = m,
                    )
                }
            }
        }
        val list: @Composable (Modifier) -> Unit = { m ->
            IptvPlaylistListPane(
                rows = accounts.map { rowModel(it) },
                selectedKey = selectedKey,
                addSelected = mode == PaneMode.Add,
                pending = pane.pending?.takeIf { it.origin == PopoverOrigin.Row },
                onSelect = { IptvSettingsPane.select(it); narrowShowsPane = true },
                onOpenDetails = { narrowShowsPane = true },
                onAdd = { IptvSettingsPane.openAdd(); narrowShowsPane = true },
                onGuideRegions = onGuideRegions,
                guideRegionsSummary = guideRegionsSummary,
                onMenuAction = { action, account ->
                    act(action, account)
                    if (action != PlaylistMenuAction.Detach && action != PlaylistMenuAction.Remove) narrowShowsPane = true
                },
                onConfirmDestructive = ::confirm,
                onDismissDestructive = IptvSettingsPane::dismissDestructive,
                onAskRemove = { IptvSettingsPane.askDestructive(DestructiveAction.REMOVE, it, PopoverOrigin.Row) },
                modifier = m,
            )
        }

        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                list(Modifier.width(300.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.width(1.dp).fillMaxHeight().background(tokens.colors.borderSubtle))
                Spacer(Modifier.width(12.dp))
                rightPane(Modifier.weight(1f))
            }
        } else if (narrowShowsPane) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.clickable { narrowShowsPane = false }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = tokens.colors.textPrimary, modifier = Modifier.size(20.dp))
                    Text(stringResource(Res.string.provider_desktop_back_to_list), color = tokens.colors.textPrimary)
                }
                rightPane(Modifier.weight(1f))
            }
        } else {
            list(Modifier.fillMaxSize())
        }
    }

    hiddenFor?.let { account ->
        IptvHiddenItemsDialog(
            playlistName = PlaylistAddress.displayName(account.name),
            state = hiddenState,
            onUnhide = { hiddenController.unhide(account, it) },
            onRetry = { hiddenController.open(account) },
            onDismiss = { hiddenFor = null },
        )
    }
}

/** "Managed by X · N days left" for a list row (just "Managed by X" until the panel has answered). */
@Composable
private fun managedRowLine(info: ManagedInfo, panel: XtreamAccountInfo?, nowSec: Long): String {
    val expiry = panel?.let { ManagedDetailsModel.expiryOf(it, nowSec) }
    return when (expiry) {
        is ExpiryDisplay.DaysLeft ->
            if (expiry.days == 1) stringResource(Res.string.provider_managed_row_day, info.providerName)
            else stringResource(Res.string.provider_managed_row_days, info.providerName, expiry.days)
        ExpiryDisplay.Expired -> stringResource(Res.string.provider_managed_row_expired, info.providerName)
        else -> stringResource(Res.string.provider_managed_row, info.providerName)
    }
}
