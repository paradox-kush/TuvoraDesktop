package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaVideo
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episodes_go_to_episode
import nuvio.composeapp.generated.resources.episodes_jump_hint
import nuvio.composeapp.generated.resources.episodes_jump_not_found
import nuvio.composeapp.generated.resources.episodes_jump_placeholder
import org.jetbrains.compose.resources.stringResource

/**
 * Type an episode number to jump straight to it. Soap viewers usually know the number they want,
 * and stepping through twenty range buckets to reach episode 847 is its own chore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JumpToEpisodeSheet(
    episodes: List<MetaVideo>,
    onDismiss: () -> Unit,
    onJump: (index: Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var notFound by remember { mutableStateOf(false) }

    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Text(
                text = stringResource(Res.string.episodes_go_to_episode),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
            )
            NuvioInputField(
                value = query,
                onValueChange = {
                    query = it.filter(Char::isDigit).take(6)
                    notFound = false
                },
                placeholder = stringResource(Res.string.episodes_jump_placeholder),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (notFound) {
                    stringResource(Res.string.episodes_jump_not_found)
                } else {
                    stringResource(Res.string.episodes_jump_hint, episodes.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (notFound) tokens.colors.danger else tokens.colors.textMuted,
            )
            NuvioPrimaryButton(
                text = stringResource(Res.string.episodes_go_to_episode),
                onClick = {
                    val wanted = query.trim().toIntOrNull()
                    val index = wanted?.let { number ->
                        episodes.indexOfFirst { it.episode == number }
                    } ?: -1
                    if (index < 0) {
                        notFound = true
                    } else {
                        onJump(index)
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
