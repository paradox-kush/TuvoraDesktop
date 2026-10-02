package com.nuvio.app.features.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSwitchRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_desktop_contact_heading
import nuvio.composeapp.generated.resources.provider_desktop_rename_hint
import nuvio.composeapp.generated.resources.provider_desktop_rename_save
import nuvio.composeapp.generated.resources.provider_desktop_section_library
import nuvio.composeapp.generated.resources.provider_desktop_section_manage
import nuvio.composeapp.generated.resources.provider_details_channels
import nuvio.composeapp.generated.resources.provider_details_connections
import nuvio.composeapp.generated.resources.provider_details_day_left
import nuvio.composeapp.generated.resources.provider_details_days_left
import nuvio.composeapp.generated.resources.provider_details_detach
import nuvio.composeapp.generated.resources.provider_details_edit
import nuvio.composeapp.generated.resources.provider_details_edit_hint
import nuvio.composeapp.generated.resources.provider_details_enabled
import nuvio.composeapp.generated.resources.provider_details_enabled_hint
import nuvio.composeapp.generated.resources.provider_details_expired
import nuvio.composeapp.generated.resources.provider_details_expiry_check_failed
import nuvio.composeapp.generated.resources.provider_details_expiry_not_reported
import nuvio.composeapp.generated.resources.provider_details_locked_title
import nuvio.composeapp.generated.resources.provider_details_locked_value
import nuvio.composeapp.generated.resources.provider_details_movies
import nuvio.composeapp.generated.resources.provider_details_never_expires
import nuvio.composeapp.generated.resources.provider_details_remove
import nuvio.composeapp.generated.resources.provider_details_ribbon
import nuvio.composeapp.generated.resources.provider_details_ribbon_updated
import nuvio.composeapp.generated.resources.provider_details_series
import nuvio.composeapp.generated.resources.provider_details_status_loading
import nuvio.composeapp.generated.resources.provider_details_status_unreachable
import nuvio.composeapp.generated.resources.provider_details_tile_categories
import nuvio.composeapp.generated.resources.provider_details_tile_categories_hint
import nuvio.composeapp.generated.resources.provider_details_tile_hidden
import nuvio.composeapp.generated.resources.provider_details_tile_hidden_hint
import nuvio.composeapp.generated.resources.provider_details_tile_rematch
import nuvio.composeapp.generated.resources.provider_details_tile_rematch_hint
import nuvio.composeapp.generated.resources.provider_edit_name_label
import org.jetbrains.compose.resources.stringResource

/**
 * The right pane for a playlist: ONE scrollable page, no tabs. Header (name, managed ribbon only when managed,
 * days left with a thin bar only when the provider reports an expiry, connections, catalog counts, contact
 * buttons), then Manage and Your library. Everything is built by [ManagedDetailsModel]; nothing here fetches or
 * decides.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IptvPlaylistDetailPane(
    model: ManagedDetailsModel,
    live: PlaylistDetailsLive,
    account: XtreamAccount,
    renaming: Boolean,
    pending: PendingDestructive?,
    onStartRename: () -> Unit,
    onRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onAction: (PlaylistMenuAction) -> Unit,
    onAskDestructive: (DestructiveAction) -> Unit,
    onConfirmDestructive: (DestructiveAction) -> Unit,
    onDismissDestructive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- header ----
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NuvioTokens.Radius.xl)).background(tokens.colors.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            model.managedBy?.let {
                val updated = PlaylistAddress.isoDate(model.serviceUpdatedAt)
                RibbonChip(
                    text = if (updated != null) stringResource(Res.string.provider_details_ribbon_updated, model.providerName.orEmpty(), updated)
                    else stringResource(Res.string.provider_details_ribbon, model.providerName.orEmpty()),
                    leading = { Icon(Icons.Rounded.Link, null, tint = tokens.colors.accent, modifier = Modifier.size(14.dp)) },
                )
            }
            Text(
                model.name, style = MaterialTheme.typography.headlineMedium, color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            model.addressLine?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val statusLine = when {
                live.hasPanel && live.loading && live.info == null -> stringResource(Res.string.provider_details_status_loading)
                live.hasPanel && !live.loading && live.info == null -> stringResource(Res.string.provider_details_status_unreachable)
                else -> model.statusText
            }
            statusLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpiryTile(model.expiry, loading = live.loading)
                model.connections?.let { c ->
                    StatTile(Modifier.widthIn(min = 200.dp)) {
                        Text(
                            stringResource(Res.string.provider_details_connections, c.active, c.max),
                            style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                model.counts.channels?.let { CountTile(it, Res.string.provider_details_channels) }
                model.counts.movies?.let { CountTile(it, Res.string.provider_details_movies) }
                model.counts.series?.let { CountTile(it, Res.string.provider_details_series) }
            }
            if (model.contacts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.provider_desktop_contact_heading, model.providerName.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                    )
                    DesktopContactButtons(model.contacts)
                }
            }
        }

        // ---- manage ----
        SectionTitle(stringResource(Res.string.provider_desktop_section_manage))
        SettingsGroup(isTablet = true) {
            NameRow(name = model.name, renaming = renaming, onStartRename = onStartRename, onRename = onRename, onCancelRename = onCancelRename)
            SettingsGroupDivider(isTablet = true)
            ServerLoginRow(locked = model.lockedServerLogin, providerName = model.providerName.orEmpty(), onEdit = { onAction(PlaylistMenuAction.EditServerLogin) })
            SettingsGroupDivider(isTablet = true)
            SettingsSwitchRow(
                title = stringResource(Res.string.provider_details_enabled),
                description = stringResource(Res.string.provider_details_enabled_hint),
                checked = model.enabled,
                isTablet = true,
                onCheckedChange = { onAction(PlaylistMenuAction.ToggleEnabled) },
            )
        }

        SectionTitle(stringResource(Res.string.provider_desktop_section_library))
        SettingsGroup(isTablet = true) {
            SettingsNavigationRow(
                title = stringResource(Res.string.provider_details_tile_categories),
                description = stringResource(Res.string.provider_details_tile_categories_hint),
                isTablet = true,
                onClick = { onAction(PlaylistMenuAction.Content) },
            )
            SettingsGroupDivider(isTablet = true)
            SettingsNavigationRow(
                title = stringResource(Res.string.provider_details_tile_hidden),
                description = stringResource(Res.string.provider_details_tile_hidden_hint),
                isTablet = true,
                onClick = { onAction(PlaylistMenuAction.Hidden) },
            )
            if (DetailsAction.REMATCH in model.groups.flatMap { it.actions }) {
                SettingsGroupDivider(isTablet = true)
                SettingsNavigationRow(
                    title = stringResource(Res.string.provider_details_tile_rematch),
                    description = stringResource(Res.string.provider_details_tile_rematch_hint),
                    isTablet = true,
                    onClick = { onAction(PlaylistMenuAction.Rematch) },
                )
            }
        }

        // ---- destructive ----
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            if (DetailsAction.DETACH in model.groups.flatMap { it.actions }) {
                DestructiveButton(
                    label = stringResource(Res.string.provider_details_detach, model.providerName.orEmpty()),
                    action = DestructiveAction.DETACH,
                    pending = pending?.takeIf { it.action == DestructiveAction.DETACH },
                    playlistName = model.name, providerName = model.providerName, managed = model.isManaged,
                    onAsk = { onAskDestructive(DestructiveAction.DETACH) },
                    onConfirm = { onConfirmDestructive(DestructiveAction.DETACH) },
                    onDismiss = onDismissDestructive,
                )
            }
            DestructiveButton(
                label = stringResource(Res.string.provider_details_remove),
                action = DestructiveAction.REMOVE,
                pending = pending?.takeIf { it.action == DestructiveAction.REMOVE },
                playlistName = model.name, providerName = model.providerName, managed = model.isManaged,
                onAsk = { onAskDestructive(DestructiveAction.REMOVE) },
                onConfirm = { onConfirmDestructive(DestructiveAction.REMOVE) },
                onDismiss = onDismissDestructive,
            )
        }
    }
}

@Composable
private fun ExpiryTile(expiry: ExpiryDisplay, loading: Boolean) {
    val tokens = MaterialTheme.nuvio
    val text: String? = when (expiry) {
        is ExpiryDisplay.DaysLeft ->
            if (expiry.days == 1) stringResource(Res.string.provider_details_day_left) else stringResource(Res.string.provider_details_days_left, expiry.days)
        ExpiryDisplay.Expired -> stringResource(Res.string.provider_details_expired)
        ExpiryDisplay.NeverExpires -> stringResource(Res.string.provider_details_never_expires)
        is ExpiryDisplay.Text -> expiry.text
        // While the panel has not answered there is nothing to say yet.
        ExpiryDisplay.NotReported -> if (loading) null else stringResource(Res.string.provider_details_expiry_not_reported)
        ExpiryDisplay.CheckFailed -> stringResource(Res.string.provider_details_expiry_check_failed)
    }
    if (text == null) return
    StatTile(Modifier.widthIn(min = 200.dp)) {
        Text(
            text, style = MaterialTheme.typography.titleMedium,
            color = if (expiry == ExpiryDisplay.Expired) tokens.colors.danger else tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold,
        )
        // The bar exists only in the last 30 days of a subscription (the model hands a fraction only then).
        (expiry as? ExpiryDisplay.DaysLeft)?.fraction?.let { ThinBar(it) }
    }
}

@Composable
private fun CountTile(count: Int, label: org.jetbrains.compose.resources.StringResource) {
    val tokens = MaterialTheme.nuvio
    StatTile {
        Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
    }
}

/** The name, inline-editable: click (or the menu's Rename) turns it into a field; Return saves, Esc cancels. */
@Composable
private fun NameRow(name: String, renaming: Boolean, onStartRename: () -> Unit, onRename: (String) -> Unit, onCancelRename: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().then(if (!renaming) Modifier.clickable(onClick = onStartRename) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.provider_edit_name_label), style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 120.dp),
        )
        if (renaming) {
            var value by remember(name) { mutableStateOf(TextFieldValue(name, TextRange(name.length))) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.accent),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                    .background(tokens.colors.surfaceCard)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> { onRename(value.text); true }
                            Key.Escape -> { onCancelRename(); true }
                            else -> false
                        }
                    },
            )
            Text(
                stringResource(Res.string.provider_desktop_rename_save), style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.accent, modifier = Modifier.clickable { onRename(value.text) },
            )
        } else {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.Edit, contentDescription = stringResource(Res.string.provider_desktop_rename_hint), tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}

/** Own playlist: an Edit entry. Managed: greyed, locked, WITH the reason (never hidden). */
@Composable
private fun ServerLoginRow(locked: Boolean, providerName: String, onEdit: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    if (!locked) {
        SettingsNavigationRow(
            title = stringResource(Res.string.provider_details_edit),
            description = stringResource(Res.string.provider_details_edit_hint),
            isTablet = true,
            onClick = onEdit,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = tokens.colors.textDisabled, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.provider_details_locked_title), style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textDisabled, fontWeight = FontWeight.Medium)
            Text(
                stringResource(Res.string.provider_details_locked_value, providerName),
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
            )
        }
    }
}

/** A danger-outlined button that hosts its own typed-word popover. */
@Composable
private fun DestructiveButton(
    label: String,
    action: DestructiveAction,
    pending: PendingDestructive?,
    playlistName: String,
    providerName: String?,
    managed: Boolean,
    onAsk: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box {
        OutlinedButton(
            onClick = onAsk,
            shape = tokens.shapes.button,
            border = BorderStroke(NuvioTokens.Border.thin, tokens.colors.danger),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.colors.danger),
        ) { Text(label) }
        if (pending != null) {
            DestructivePopover(action = action, playlistName = playlistName, providerName = providerName, managed = managed, onConfirm = onConfirm, onDismiss = onDismiss)
        }
    }
}
