package com.nuvio.app.features.announcements.internal

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.announcements.api.Announcement
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.announcement_dismiss
import org.jetbrains.compose.resources.stringResource

/**
 * Stateless announcement card — the same surface/typography/button vocabulary as the home screen's
 * empty-state and offline cards (NuvioSurfaceCard + NuvioPrimaryButton + nuvio tokens). Kept
 * compact because it sits above the hero: title row (with dismiss), body, optional CTA.
 */
@Composable
internal fun AnnouncementCard(
    announcement: Announcement,
    onCtaClick: (url: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = announcement.title,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
            )
            Spacer(modifier = Modifier.width(tokens.spacing.controlGap))
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.announcement_dismiss),
                    tint = tokens.colors.textMuted,
                )
            }
        }
        if (announcement.body.isNotBlank()) {
            Text(
                text = announcement.body,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }
        val label = announcement.ctaLabel
        val url = announcement.ctaUrl
        if (label != null && url != null) {
            Spacer(modifier = Modifier.height(tokens.spacing.controlGap))
            NuvioPrimaryButton(
                text = label,
                onClick = { onCtaClick(url) },
            )
        }
    }
}
