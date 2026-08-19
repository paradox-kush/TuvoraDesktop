package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.watching.application.WatchingState

@Composable
fun HomeCatalogRowSection(
    section: HomeCatalogSection,
    modifier: Modifier = Modifier,
    entries: List<MetaPreview> = section.items,
    watchedKeys: Set<String> = emptySet(),
    fullyWatchedSeriesKeys: Set<String> = emptySet(),
    sectionPadding: Dp? = null,
    onViewAllClick: (() -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
) {
    if (sectionPadding != null) {
        HomeCatalogRowSectionContent(
            section = section,
            entries = entries,
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            modifier = modifier.fillMaxWidth(),
            sectionPadding = sectionPadding,
            onViewAllClick = onViewAllClick,
            onPosterClick = onPosterClick,
            onPosterLongClick = onPosterLongClick,
        )
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            HomeCatalogRowSectionContent(
                section = section,
                entries = entries,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                modifier = Modifier.fillMaxWidth(),
                sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value),
                onViewAllClick = onViewAllClick,
                onPosterClick = onPosterClick,
                onPosterLongClick = onPosterLongClick,
            )
        }
    }
}

@Composable
private fun HomeCatalogRowSectionContent(
    section: HomeCatalogSection,
    entries: List<MetaPreview>,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    modifier: Modifier,
    sectionPadding: Dp,
    onViewAllClick: (() -> Unit)?,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()

    // Clicks carry the row context that playback events cannot: a play_start knows only the item,
    // so without this there is no way to answer "which row caused this watch?" — the question the
    // recommender is actually being trained to answer. Wrapped once per row; the slot lookup is an
    // index scan over one rowful, paid only on an actual click.
    val onPosterClickLogged: ((MetaPreview) -> Unit)? =
        remember(onPosterClick, entries, section.key) {
            onPosterClick?.let { delegate ->
                { preview: MetaPreview ->
                    com.nuvio.app.core.rec.recLogClick(
                        surface = com.nuvio.app.core.rec.RecSurface.HOME,
                        rowId = section.key,
                        rowIndex = null,
                        itemPosition = entries.indexOfFirst { it.id == preview.id }
                            .takeIf { it >= 0 },
                        item = com.nuvio.app.core.rec.RecImpressionItem(
                            itemId = preview.id,
                            contentType = com.nuvio.app.core.rec.recContentTypeOf(
                                contentType = preview.type,
                                season = null,
                                episode = null,
                            ),
                        ),
                    )
                    delegate(preview)
                }
            }
        }

    NuvioShelfSection(
        title = section.title,
        entries = entries,
        modifier = modifier,
        headerHorizontalPadding = sectionPadding,
        rowContentPadding = PaddingValues(horizontal = sectionPadding),
        onViewAllClick = onViewAllClick,
        viewAllPillSize = NuvioViewAllPillSize.Compact,
        key = { item -> item.stableKey() },
        // section.key is the catalogue's stable identity (addon + catalog), which is what makes
        // "this row performs better than that one" answerable across sessions and releases.
        // The shelf exposes only its scroll state (Invariant S); home owns the rec mapping and
        // attaches the impression observer here, so core/ui never imports the rec subsystem.
        impressionsAttach = { listState ->
            com.nuvio.app.core.rec.RecRowImpressions(
                listState = listState,
                surface = com.nuvio.app.core.rec.RecSurface.HOME,
                rowId = section.key,
                rowIndex = null,
                itemAt = { index ->
                    entries.getOrNull(index)?.let { preview ->
                        com.nuvio.app.core.rec.RecImpressionItem(
                            itemId = preview.id,
                            contentType = com.nuvio.app.core.rec.recContentTypeOf(
                                contentType = preview.type,
                                season = null,
                                episode = null,
                            ),
                        )
                    }
                },
            )
        },
    ) { item ->
        HomePosterCard(
            item = item,
            useLandscapeBackdropMode = posterCardStyle.catalogLandscapeModeEnabled,
            isWatched = WatchingState.isPosterWatched(
                watchedKeys = watchedKeys,
                item = item,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            ),
            onClick = onPosterClickLogged?.let { { it(item) } },
            onLongClick = onPosterLongClick?.let { { it(item) } },
        )
    }
}
