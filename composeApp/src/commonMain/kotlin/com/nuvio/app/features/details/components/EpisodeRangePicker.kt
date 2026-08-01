package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.EpisodeBucket
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episodes_go_to_episode
import nuvio.composeapp.generated.resources.episodes_range
import org.jetbrains.compose.resources.stringResource

/**
 * Range picker for seasons too long to scroll — a soap opera season can run past a thousand
 * episodes, so the list is sliced into buckets and only one is shown at a time.
 */
@Composable
internal fun EpisodeRangePicker(
    buckets: List<EpisodeBucket>,
    selected: EpisodeBucket?,
    onSelect: (EpisodeBucket) -> Unit,
    onJumpToEpisode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (buckets.isEmpty()) return
    val tokens = MaterialTheme.nuvio
    val rangeTitle = stringResource(Res.string.episodes_range)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioDropdownChip(
            title = rangeTitle,
            label = selected?.label ?: buckets.first().label,
            selectedKey = selected?.label,
            options = buckets.map { NuvioDropdownOption(key = it.label, label = it.label) },
            onSelected = { option ->
                buckets.firstOrNull { it.label == option.key }?.let(onSelect)
            },
        )

        Row(
            modifier = Modifier
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.surface)
                .clickable(onClick = onJumpToEpisode)
                .padding(
                    horizontal = NuvioTokens.Space.s12,
                    vertical = tokens.components.chipVerticalPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(tokens.icons.sm),
            )
            Text(
                text = stringResource(Res.string.episodes_go_to_episode),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}
