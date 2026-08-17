package com.nuvio.app.features.epg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Human-readable summary of a selection, for the settings row's description line.
 * Pure so it can be tested and reused by every platform's row.
 */
internal fun epgRegionSummary(selected: Set<String>, available: List<EpgRegion>): String = when {
    available.isEmpty() -> "Available after the first guide sync"
    selected.isEmpty() -> "All regions (${available.size})"
    else -> {
        val flags = available.filter { it.name in selected }.mapNotNull { it.flag.ifEmpty { null } }
        val names = selected.sorted()
        val head = names.take(3).joinToString(", ")
        val tail = if (names.size > 3) " +${names.size - 3}" else ""
        (flags.take(3).joinToString(" ") + "  ").takeIf { flags.isNotEmpty() }.orEmpty() + head + tail
    }
}

/**
 * Region picker for the EPG mirror.
 *
 * The mirror carries every region it can find, but a household uses a fraction: on a measured
 * 11k-channel panel only 2,035 of 15,397 indexed EPG channels ever matched. Unselected regions
 * are never stored, so trimming here shrinks the on-device index and the per-channel match walk
 * rather than just hiding rows.
 *
 * Selecting nothing means "all regions" — the picker is opt-in and an untouched install keeps
 * its current behaviour.
 */
@Composable
internal fun EpgRegionPickerDialog(
    available: List<EpgRegion>,
    initiallySelected: Set<String>,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit,
) {
    var selected by remember(initiallySelected) { mutableStateOf(initiallySelected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guide regions") },
        text = {
            Column {
                Text(
                    text = if (selected.isEmpty()) {
                        "Using every region. Pick the ones you watch to shrink the guide data " +
                            "stored on this device."
                    } else {
                        "${selected.size} selected. Clearing all goes back to using every region."
                    },
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(available, key = { it.name }) { region ->
                        val checked = region.name in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - region.name else selected + region.name
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(10.dp))
                            if (region.flag.isNotEmpty()) {
                                Text(region.flag, fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(region.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${region.channelCount} channels",
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { selected = emptySet() }) { Text("Use all") }
                }
                TextButton(onClick = { onApply(selected) }) { Text("Apply") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Loads the catalog and current selection, then shows [EpgRegionPickerDialog].
 * Applying rebuilds the mirror index against the new selection on the next sync.
 */
@Composable
internal fun EpgRegionPickerHost(onDismiss: () -> Unit) {
    var available by remember { mutableStateOf<List<EpgRegion>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        available = EpgMirrorRepository.availableRegions()
        selected = EpgMirrorRepository.selectedRegions()
        loaded = true
    }

    if (!loaded) return
    EpgRegionPickerDialog(
        available = available,
        initiallySelected = selected,
        onDismiss = onDismiss,
        onApply = { picked ->
            // setSelectedRegions rebuilds on the repository's own scope, so dismissing here
            // cannot cancel the sync.
            scope.launch { EpgMirrorRepository.setSelectedRegions(picked) }
            onDismiss()
        },
    )
}
