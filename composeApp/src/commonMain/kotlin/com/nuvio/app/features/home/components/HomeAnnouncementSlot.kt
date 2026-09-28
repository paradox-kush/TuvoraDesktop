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

/**
 * Where the card must start, measured from the top of the screen. Pure, so it tests without a
 * device. The status-bar-only [defaultTopInset] is enough on phones (their tab bar is at the
 * bottom), but tablets draw a floating tab-bar pill OVER the top of the content — [topNavOverlay]
 * is that pill's full height (status bar included), 0 when there is none — and desktop reserves
 * [topChromePadding] for its top bar. The card clears whichever is lowest, plus [gap] below a pill.
 */
internal fun homeAnnouncementTopInset(
    defaultTopInset: Dp,
    topChromePadding: Dp?,
    topNavOverlay: Dp,
    gap: Dp = 8.dp,
): Dp {
    var inset = defaultTopInset
    if (topChromePadding != null && topChromePadding > inset) inset = topChromePadding
    if (topNavOverlay > 0.dp && topNavOverlay + gap > inset) inset = topNavOverlay + gap
    return inset
}

/** As a plain list item (no hero), the list already pads by [listTopPadding]; add only the rest. */
internal fun homeAnnouncementItemTopInset(required: Dp, listTopPadding: Dp): Dp =
    (required - listTopPadding).coerceAtLeast(0.dp)
