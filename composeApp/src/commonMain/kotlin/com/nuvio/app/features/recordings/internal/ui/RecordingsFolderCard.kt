package com.nuvio.app.features.recordings.internal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.recordings_folder_change
import nuvio.composeapp.generated.resources.recordings_folder_not_writable
import nuvio.composeapp.generated.resources.recordings_folder_note
import nuvio.composeapp.generated.resources.recordings_folder_title
import org.jetbrains.compose.resources.stringResource

/** Where new recordings are saved, and the action to change it. Pure drawing; [RecordingsFolderController] decides. */
@Composable
internal fun RecordingsFolderCard(controller: RecordingsFolderController) {
    val colors = MaterialTheme.nuvio.colors
    val path by controller.path.collectAsStateWithLifecycle()
    val notWritable by controller.notWritable.collectAsStateWithLifecycle()
    val choosing by controller.choosing.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Text(
                text = stringResource(Res.string.recordings_folder_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.recordings_folder_note),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            if (notWritable) {
                Text(
                    text = stringResource(Res.string.recordings_folder_not_writable),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.danger,
                )
            }
            TextButton(onClick = controller::change, enabled = !choosing) {
                Text(stringResource(Res.string.recordings_folder_change))
            }
        }
    }
}
