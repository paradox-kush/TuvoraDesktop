package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.contracts.HomeAnnouncementsSection
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioPlatformExtraTopPadding

/**
 * The announcement card's slot at the very top of Home. When the hero is shown the list has no top
 * padding (the hero draws under the status bar), so the slot supplies the same top inset the screen
 * would otherwise use; [onHeightChanged] lets Home shrink the hero's viewport by the card's height so
 * the hero + continue-watching still fit on first paint.
 */
@Composable
internal fun HomeAnnouncementSlot(
    section: HomeAnnouncementsSection,
    horizontalPadding: Dp,
    topInset: Dp,
    onHeightChanged: (Int) -> Unit = {},
) {
    Box(modifier = Modifier.onSizeChanged { onHeightChanged(it.height) }) {
        section.Render(
            modifier = Modifier.padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = topInset,
                bottom = 8.dp,
            ),
        )
    }
}

/** The list's default top inset (status bar + screen top + platform extra), for the hero case. */
@Composable
internal fun homeAnnouncementDefaultTopInset(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        MaterialTheme.nuvio.spacing.screenTop +
        nuvioPlatformExtraTopPadding
