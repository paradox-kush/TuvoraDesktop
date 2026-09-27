package com.nuvio.app.features.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.iptv.overlay.HiddenItemsUiState
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.HiddenItem
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.HiddenKind

/**
 * "Hidden channels & groups" for one playlist (F02): everything hidden on this device or on the
 * website, each with Unhide. Renders [state] only; the controller owns loading and the writes.
 */
@Composable
internal fun IptvHiddenItemsDialog(
    playlistName: String,
    state: HiddenItemsUiState,
    onUnhide: (HiddenItem) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden in $playlistName") },
        text = {
            when {
                state.loading -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
                state.items.isEmpty() -> Text(
                    "Nothing is hidden in this playlist. To hide a channel, long-press it in the Live TV guide; " +
                        "to hide a group, open it in the IPTV hub and choose Hide group.",
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(state.items, key = { it.kind.name + it.key }) { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = MaterialTheme.nuvio.colors.textPrimary)
                                Text(hiddenItemKindLabel(item), color = MaterialTheme.nuvio.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onUnhide(item) }) { Text("Unhide") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

internal fun hiddenItemKindLabel(item: HiddenItem): String = when (item.kind) {
    HiddenKind.CHANNEL -> "Channel"
    HiddenKind.GROUP -> when (item.contentType) {
        CONTENT_TYPE_MOVIES -> "Movie group"
        CONTENT_TYPE_SERIES -> "Series group"
        else -> "Channel group"
    }
}
