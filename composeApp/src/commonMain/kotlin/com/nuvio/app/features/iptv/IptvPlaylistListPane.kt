package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.secondaryClickAt
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_desktop_add_row
import nuvio.composeapp.generated.resources.provider_desktop_disabled_suffix
import nuvio.composeapp.generated.resources.provider_desktop_guide_regions
import nuvio.composeapp.generated.resources.provider_desktop_list_heading
import nuvio.composeapp.generated.resources.provider_desktop_menu_disable
import nuvio.composeapp.generated.resources.provider_desktop_menu_enable
import nuvio.composeapp.generated.resources.provider_desktop_menu_locked_reason
import nuvio.composeapp.generated.resources.provider_desktop_menu_more
import nuvio.composeapp.generated.resources.provider_desktop_menu_open
import nuvio.composeapp.generated.resources.provider_desktop_menu_rename
import nuvio.composeapp.generated.resources.provider_details_detach
import nuvio.composeapp.generated.resources.provider_details_edit
import nuvio.composeapp.generated.resources.provider_details_remove
import nuvio.composeapp.generated.resources.provider_details_tile_categories
import nuvio.composeapp.generated.resources.provider_details_tile_hidden
import nuvio.composeapp.generated.resources.provider_details_tile_rematch
import org.jetbrains.compose.resources.stringResource

/** What the row menu (hover "…" and right-click share it) and the details page can ask for. */
internal enum class PlaylistMenuAction { Open, Rename, ToggleEnabled, Content, Hidden, Rematch, EditServerLogin, Detach, Remove }

/** A row of the playlist list, already resolved to what it shows (no I/O in the composable). */
internal data class PlaylistRowModel(val account: XtreamAccount, val managed: ManagedInfo?, val subtitle: String)

/**
 * The left pane: one row per playlist (selected row marked by a bar and a tint, managed rows with a link icon
 * and "Managed by X · N days left"), then "+ Add a playlist" and "Guide regions". The list is one focus
 * target: Up/Down move the selection, Return opens (moves into the details), Delete asks to remove — through
 * the same typed-word popover as the button — and Esc closes a menu or popover. Hover reveals a "…" button
 * and right-click opens the same menu; neither is required.
 */
@Composable
internal fun IptvPlaylistListPane(
    rows: List<PlaylistRowModel>,
    selectedKey: String?,
    addSelected: Boolean,
    pending: PendingDestructive?,
    onSelect: (String) -> Unit,
    onOpenDetails: () -> Unit,
    onAdd: () -> Unit,
    onGuideRegions: () -> Unit,
    guideRegionsSummary: String?,
    onMenuAction: (PlaylistMenuAction, XtreamAccount) -> Unit,
    onConfirmDestructive: (DestructiveAction, XtreamAccount) -> Unit,
    onDismissDestructive: () -> Unit,
    onAskRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val keys = rows.map { it.account.id }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .focusRequester(focus)
            .onFocusChanged { focused = it.hasFocus }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || pending != null) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { IptvSettingsPane.step(keys, selectedKey, +1)?.let(onSelect); true }
                    Key.DirectionUp -> { IptvSettingsPane.step(keys, selectedKey, -1)?.let(onSelect); true }
                    Key.Enter, Key.NumPadEnter -> { if (!addSelected && selectedKey != null) onOpenDetails(); true }
                    Key.Delete, Key.Backspace -> { selectedKey?.let(onAskRemove); selectedKey != null }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(Res.string.provider_desktop_list_heading),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.Bold,
        )
        rows.forEach { row ->
            PlaylistRow(
                row = row,
                selected = row.account.id == selectedKey && !addSelected,
                listFocused = focused,
                pending = pending?.takeIf { it.playlistKey == row.account.id && it.origin == PopoverOrigin.Row },
                onSelect = { focus.requestFocus(); onSelect(row.account.id) },
                onMenuAction = { onMenuAction(it, row.account) },
                onConfirmDestructive = { onConfirmDestructive(it, row.account) },
                onDismissDestructive = onDismissDestructive,
            )
        }
        Spacer(Modifier.height(6.dp))
        ListActionRow(
            icon = { Icon(Icons.Rounded.Add, null, tint = tokens.colors.accent, modifier = Modifier.size(20.dp)) },
            title = stringResource(Res.string.provider_desktop_add_row),
            accent = true,
            selected = addSelected,
            onClick = { focus.requestFocus(); onAdd() },
        )
        ListActionRow(
            icon = null,
            title = stringResource(Res.string.provider_desktop_guide_regions),
            subtitle = guideRegionsSummary,
            accent = false,
            selected = false,
            onClick = onGuideRegions,
        )
    }
}

@Composable
private fun PlaylistRow(
    row: PlaylistRowModel,
    selected: Boolean,
    listFocused: Boolean,
    pending: PendingDestructive?,
    onSelect: () -> Unit,
    onMenuAction: (PlaylistMenuAction) -> Unit,
    onConfirmDestructive: (DestructiveAction) -> Unit,
    onDismissDestructive: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTokens.Radius.md)
    val background = when {
        selected -> tokens.colors.accent.copy(alpha = tokens.opacity.selected)
        hovered -> tokens.colors.overlayHover
        else -> Color.Transparent
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(background, shape)
                .then(if (selected && listFocused) Modifier.border(NuvioTokens.Border.medium, tokens.colors.borderFocus, shape) else Modifier)
                .hoverable(interaction)
                .secondaryClickAt { menuOpen = true }
                .clickable(onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The selected row is marked by a bar as well as the tint, so selection never relies on colour alone.
            Box(Modifier.size(width = 3.dp, height = 28.dp).background(if (selected) tokens.colors.accent else Color.Transparent, RoundedCornerShape(2.dp)))
            if (row.managed != null) {
                Icon(Icons.Rounded.Link, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.subtitle + if (!row.account.enabled) "  •  " + stringResource(Res.string.provider_desktop_disabled_suffix) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.managed != null) tokens.colors.accent else tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hovered || menuOpen) {
                Box(
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(NuvioTokens.Radius.sm)).clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(Res.string.provider_desktop_menu_more),
                        tint = tokens.colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        PlaylistRowMenu(
            expanded = menuOpen,
            account = row.account,
            managed = row.managed,
            onDismiss = { menuOpen = false },
            onAction = { menuOpen = false; onMenuAction(it) },
        )
        if (pending != null) {
            DestructivePopover(
                action = pending.action,
                playlistName = row.account.name,
                providerName = row.managed?.providerName,
                managed = row.managed != null,
                onConfirm = { onConfirmDestructive(pending.action) },
                onDismiss = onDismissDestructive,
            )
        }
    }
}

/** The one menu: hover "…" and right-click both open it. Unavailable actions are greyed WITH the reason, not hidden. */
@Composable
private fun PlaylistRowMenu(
    expanded: Boolean,
    account: XtreamAccount,
    managed: ManagedInfo?,
    onDismiss: () -> Unit,
    onAction: (PlaylistMenuAction) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = tokens.colors.surfacePopover) {
        DropdownMenuItem(text = { Text(stringResource(Res.string.provider_desktop_menu_open)) }, onClick = { onAction(PlaylistMenuAction.Open) })
        DropdownMenuItem(text = { Text(stringResource(Res.string.provider_desktop_menu_rename)) }, onClick = { onAction(PlaylistMenuAction.Rename) })
        DropdownMenuItem(
            text = { Text(stringResource(if (account.enabled) Res.string.provider_desktop_menu_disable else Res.string.provider_desktop_menu_enable)) },
            onClick = { onAction(PlaylistMenuAction.ToggleEnabled) },
        )
        DropdownMenuItem(text = { Text(stringResource(Res.string.provider_details_tile_categories)) }, onClick = { onAction(PlaylistMenuAction.Content) })
        DropdownMenuItem(text = { Text(stringResource(Res.string.provider_details_tile_hidden)) }, onClick = { onAction(PlaylistMenuAction.Hidden) })
        if (account.sourceType == SOURCE_TYPE_XTREAM) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.provider_details_tile_rematch)) }, onClick = { onAction(PlaylistMenuAction.Rematch) })
        }
        if (ManagedPlaylistPolicy.showEditServerLogin(managed != null)) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.provider_details_edit)) }, onClick = { onAction(PlaylistMenuAction.EditServerLogin) })
        } else {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(Res.string.provider_details_edit))
                        Text(
                            stringResource(Res.string.provider_desktop_menu_locked_reason, managed?.providerName.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                },
                leadingIcon = { Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(18.dp)) },
                enabled = false,
                onClick = {},
            )
        }
        if (managed != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.provider_details_detach, managed.providerName), color = tokens.colors.danger) },
                onClick = { onAction(PlaylistMenuAction.Detach) },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.provider_details_remove), color = tokens.colors.danger) },
            onClick = { onAction(PlaylistMenuAction.Remove) },
        )
    }
}

@Composable
private fun ListActionRow(
    icon: (@Composable () -> Unit)?,
    title: String,
    accent: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(NuvioTokens.Radius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    selected -> tokens.colors.accent.copy(alpha = tokens.opacity.selected)
                    hovered -> tokens.colors.overlayHover
                    else -> Color.Transparent
                },
                shape,
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(3.dp))
        icon?.invoke()
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (accent) tokens.colors.accent else tokens.colors.textPrimary, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted) }
        }
    }
}
