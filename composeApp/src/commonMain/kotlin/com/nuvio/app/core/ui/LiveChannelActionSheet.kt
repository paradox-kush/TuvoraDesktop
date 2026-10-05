package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_channel_action_add_favorite
import nuvio.composeapp.generated.resources.iptv_channel_action_hide
import nuvio.composeapp.generated.resources.iptv_channel_action_choose_guide
import nuvio.composeapp.generated.resources.iptv_channel_action_remove_favorite
import org.jetbrains.compose.resources.stringResource

/**
 * The long-press menu for a live channel (UX36): favourite, then hide. The Live TV guide and the
 * IPTV hub both open this, so one gesture means the same thing on both screens. Which rows show is
 * the caller's decision ([onHide] null hides that row); this sheet only renders it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioLiveChannelActionSheet(
    channel: LiveRecentActionTarget?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onHide: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** F14: pick which guide channel feeds this channel's EPG; null leaves the row out. */
    onChooseGuide: (() -> Unit)? = null,
) {
    if (channel == null) return
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    fun dismissAfter(action: () -> Unit) {
        action()
        coroutineScope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(tokens.spacing.screenHorizontal)),
        ) {
            LiveRecentSheetHeader(channel = channel)
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                title = stringResource(
                    if (isFavorite) Res.string.iptv_channel_action_remove_favorite else Res.string.iptv_channel_action_add_favorite,
                ),
                onClick = { dismissAfter(onToggleFavorite) },
            )
            if (onHide != null) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.VisibilityOff,
                    title = stringResource(Res.string.iptv_channel_action_hide),
                    onClick = { dismissAfter(onHide) },
                )
            }
            if (onChooseGuide != null) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.DateRange,
                    title = stringResource(Res.string.iptv_channel_action_choose_guide),
                    onClick = { dismissAfter(onChooseGuide) },
                )
            }
        }
    }
}
