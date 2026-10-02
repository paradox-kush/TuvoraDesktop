package com.nuvio.app.features.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard

@Composable
fun HomeEmptyStateCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    /** An outlined second action under the primary one (e.g. "I have a setup code"). */
    secondaryActionLabel: String? = null,
    onSecondaryActionClick: (() -> Unit)? = null,
    /** Extra content under the actions (e.g. a provider's contact buttons). */
    footer: (@Composable () -> Unit)? = null,
) {
    NuvioSurfaceCard(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(16.dp))
            NuvioPrimaryButton(
                text = actionLabel,
                onClick = onActionClick,
            )
        }
        if (secondaryActionLabel != null && onSecondaryActionClick != null) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onSecondaryActionClick,
                modifier = Modifier.fillMaxWidth().height(NuvioTokens.Space.s48 + NuvioTokens.Space.s4),
                shape = MaterialTheme.nuvio.shapes.button,
                border = BorderStroke(MaterialTheme.nuvio.borders.thin, MaterialTheme.nuvio.colors.accent.copy(alpha = MaterialTheme.nuvio.opacity.strong)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.nuvio.colors.accent),
            ) {
                Text(text = secondaryActionLabel, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (footer != null) {
            Spacer(modifier = Modifier.height(16.dp))
            footer()
        }
    }
}
