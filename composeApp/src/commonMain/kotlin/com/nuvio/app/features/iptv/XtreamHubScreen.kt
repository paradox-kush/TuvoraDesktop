package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.landscapePosterHeightForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.rememberHomeSkeletonBrush
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.compose_iptv_hub_add_provider
import nuvio.composeapp.generated.resources.compose_iptv_hub_empty_message
import nuvio.composeapp.generated.resources.compose_iptv_hub_empty_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_epg_next
import nuvio.composeapp.generated.resources.compose_iptv_hub_epg_no_information
import nuvio.composeapp.generated.resources.compose_iptv_hub_error_message
import nuvio.composeapp.generated.resources.compose_iptv_hub_error_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_no_provider_message
import nuvio.composeapp.generated.resources.compose_iptv_hub_no_provider_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_playlist_fallback
import nuvio.composeapp.generated.resources.compose_iptv_hub_playlists_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_section_live
import nuvio.composeapp.generated.resources.compose_settings_page_iptv_add_playlist
import nuvio.composeapp.generated.resources.library_other
import nuvio.composeapp.generated.resources.media_movies
import nuvio.composeapp.generated.resources.media_series
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Top-level IPTV browse surface: an account selector, Movies/Series section chips, and lazily
 * loaded category rows of posters. Posters open Nuvio's native detail (via the Xtream meta
 * short-circuit) and play through the normal streams -> player pipeline. Live TV is its own
 * guide (P5).
 *
 * Visually this is home's shelf system: NuvioShelfSection rows with the responsive section
 * padding, poster cards sized by the user's poster-style setting, shimmer skeletons while
 * loading, and the same safe bottom inset so the floating nav pill never covers the last row.
 */
@Composable
fun XtreamHubScreen(
    onPosterClick: (MetaPreview) -> Unit,
    onPlayLiveChannel: (String) -> Unit,
    onFavoriteLiveChannel: (String) -> Unit,
    onAddProvider: () -> Unit,
    modifier: Modifier = Modifier,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
) {
    val state by XtreamHubRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { XtreamHubRepository.ensureLoaded() }

    if (state.accounts.isEmpty()) {
        XtreamHubNoPlaylistState(onAddProvider = onAddProvider, modifier = modifier)
        return
    }

    val isLive = state.section == XtreamHubSection.LIVE
    val onTileClick: (MetaPreview) -> Unit = if (isLive) {
        { meta -> onPlayLiveChannel(meta.id) }
    } else {
        onPosterClick
    }
    val onTileLongClick: ((MetaPreview) -> Unit)? = if (isLive) {
        { meta -> onFavoriteLiveChannel(meta.id) }
    } else {
        null
    }
    val epgMap by XtreamHubRepository.epg.collectAsStateWithLifecycle()

    // Playlist-manager enforcement, all display-level (caches stay intact): disabled content
    // types lose their section chip; category selections filter the visible rows.
    val account = state.accounts.firstOrNull { it.id == state.selectedAccountId }
    val enabledSections = XtreamHubSection.entries.filter { account?.typeEnabled(it.contentKey) != false }
    val visibleCategories = if (account == null) state.categories else {
        state.categories.filter { account.allowsCategory(state.section.contentKey, it.id) }
    }
    // A category only collapses once it's confirmed empty; unloaded ones stay (as shimmer rows).
    // Filtering ahead of the LazyColumn keeps the listGap arrangement from stacking gaps for
    // collapsed rows.
    val renderableCategories = visibleCategories.filterNot { it.loaded && it.items.isEmpty() }

    val tokens = MaterialTheme.nuvio
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect { listState.animateScrollToItem(0) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(tokens.colors.background)) {
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        // In the wide/tablet layout the app's floating top nav bar overlays the top of the content,
        // which would hide this fixed section-chip header — pad it down to clear the bar.
        val tabletTopInset = if (maxWidth >= 768.dp) TABLET_TOP_BAR_INSET else 0.dp
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = tabletTopInset)) {
            XtreamHubHeader(
                accounts = state.accounts,
                selectedAccountId = state.selectedAccountId,
                sections = enabledSections,
                section = state.section,
                horizontalPadding = sectionPadding,
                onSelectAccount = { XtreamHubRepository.selectAccount(it) },
                onSelectSection = { XtreamHubRepository.selectSection(it) },
                onAddProvider = onAddProvider,
            )

            when {
                state.loadingCategories -> XtreamHubSkeleton(
                    live = isLive,
                    sectionPadding = sectionPadding,
                )

                state.loadError -> XtreamHubMessageCard(
                    title = stringResource(Res.string.compose_iptv_hub_error_title),
                    message = stringResource(Res.string.compose_iptv_hub_error_message),
                    actionLabel = stringResource(Res.string.action_retry),
                    onAction = { XtreamHubRepository.retryCategories() },
                    sectionPadding = sectionPadding,
                )

                renderableCategories.isEmpty() -> XtreamHubMessageCard(
                    title = stringResource(Res.string.compose_iptv_hub_empty_title),
                    message = stringResource(Res.string.compose_iptv_hub_empty_message),
                    actionLabel = stringResource(Res.string.compose_settings_page_iptv_add_playlist),
                    onAction = onAddProvider,
                    sectionPadding = sectionPadding,
                )

                else -> {
                    // Xtream panels ship real-world duplicate category ids — duplicate-safe keys.
                    val keyedCategories = renderableCategories.withDuplicateSafeLazyKeys { it.id }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Same rhythm and insets as home's NuvioScreen: listGap between shelves and
                        // the nav-aware safe bottom padding (the fixed 24dp before let the floating
                        // nav pill cover the last row).
                        verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                        contentPadding = PaddingValues(
                            top = NuvioTokens.Space.s4,
                            bottom = nuvioSafeBottomPadding(tokens.spacing.screenBottom),
                        ),
                    ) {
                        itemsIndexed(
                            items = keyedCategories,
                            key = { _, entry -> entry.lazyKey },
                        ) { index, entry ->
                            val category = entry.value
                            // Keyed on the account/section too: a category id that survives a
                            // section switch would otherwise keep the effect from re-running.
                            LaunchedEffect(state.selectedAccountId, state.section, category.id) {
                                XtreamHubRepository.loadCategory(category.id)
                                // Give the next few rows a head start so they land with boxes and
                                // names instead of shimmer. The repository caps how many of these
                                // can actually be in flight and drops the rest.
                                for (offset in 1..CATEGORY_PREFETCH_LOOKAHEAD) {
                                    val next = keyedCategories.getOrNull(index + offset) ?: break
                                    XtreamHubRepository.prefetchCategory(next.value.id)
                                }
                            }
                            XtreamHubCategoryRow(
                                category = category,
                                live = isLive,
                                epg = if (isLive) epgMap else emptyMap(),
                                sectionPadding = sectionPadding,
                                onPosterClick = onTileClick,
                                onPosterLongClick = onTileLongClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- header chrome ---------------------------------------------------------------

@Composable
private fun XtreamHubHeader(
    accounts: List<XtreamAccount>,
    selectedAccountId: String?,
    sections: List<XtreamHubSection>,
    section: XtreamHubSection,
    horizontalPadding: Dp,
    onSelectAccount: (String) -> Unit,
    onSelectSection: (XtreamHubSection) -> Unit,
    onAddProvider: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        sections.forEach { s ->
            XtreamHubSectionChip(
                label = stringResource(s.labelRes),
                selected = section == s,
                onClick = { onSelectSection(s) },
            )
        }
        Spacer(Modifier.weight(1f))
        // Always a dropdown so it's obvious you can switch playlists (and it lists all of them,
        // plus an "Add Playlist" entry so a second provider is one tap away).
        XtreamAccountDropdown(accounts, selectedAccountId, onSelectAccount, onAddProvider)
    }
}

private val XtreamHubSection.labelRes: StringResource
    get() = when (this) {
        XtreamHubSection.LIVE -> Res.string.compose_iptv_hub_section_live
        XtreamHubSection.MOVIES -> Res.string.media_movies
        XtreamHubSection.SERIES -> Res.string.media_series
    }

/** Section switch in the app's pill-chip vocabulary (nav pills, tablet top bar). */
@Composable
private fun XtreamHubSectionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(if (selected) tokens.colors.overlaySelected else tokens.colors.surface)
            .clickable(onClick = onClick)
            .padding(
                horizontal = tokens.components.chipHorizontalPadding,
                vertical = tokens.components.chipVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun XtreamAccountDropdown(
    accounts: List<XtreamAccount>,
    selectedAccountId: String?,
    onSelectAccount: (String) -> Unit,
    onAddPlaylist: () -> Unit,
) {
    val fallbackLabel = stringResource(Res.string.compose_iptv_hub_playlist_fallback)
    val addPlaylistLabel = stringResource(Res.string.compose_settings_page_iptv_add_playlist)
    val selectedName = accounts.firstOrNull { it.id == selectedAccountId }?.name
        ?: accounts.firstOrNull()?.name
        ?: fallbackLabel
    NuvioDropdownChip(
        title = stringResource(Res.string.compose_iptv_hub_playlists_title),
        label = selectedName,
        selectedKey = selectedAccountId,
        options = accounts.map { NuvioDropdownOption(key = it.id, label = it.name) } +
            NuvioDropdownOption(key = ADD_PLAYLIST_OPTION_KEY, label = addPlaylistLabel),
        onSelected = { option ->
            if (option.key == ADD_PLAYLIST_OPTION_KEY) onAddPlaylist() else onSelectAccount(option.key)
        },
    )
}

// --- category shelves ------------------------------------------------------------

@Composable
private fun XtreamHubCategoryRow(
    category: XtreamHubCategory,
    live: Boolean,
    epg: Map<String, ChannelEpg>,
    sectionPadding: Dp,
    onPosterClick: (MetaPreview) -> Unit,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
) {
    val title = category.name.ifBlank { stringResource(Res.string.library_other) }
    if (category.items.isEmpty()) {
        // Loading or not-yet-loaded: real title, shimmer tiles with the resolved tiles'
        // exact silhouette so nothing jumps when the row lands.
        val brush = rememberHomeSkeletonBrush()
        NuvioShelfSection(
            title = title,
            entries = remember { (0 until PLACEHOLDER_TILE_COUNT).toList() },
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            viewAllPillSize = NuvioViewAllPillSize.Compact,
            key = { it },
        ) {
            XtreamHubTilePlaceholder(live = live, brush = brush)
        }
    } else {
        NuvioShelfSection(
            title = title,
            entries = category.items,
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            viewAllPillSize = NuvioViewAllPillSize.Compact,
            key = { it.id },
        ) { item ->
            if (live) {
                // Live channel: card + now/next EPG line, fetched lazily as it appears.
                LaunchedEffect(item.id) { XtreamHubRepository.ensureEpg(item.id) }
                XtreamLiveChannelTile(
                    item = item,
                    epg = epg[item.id],
                    onClick = { onPosterClick(item) },
                    onLongClick = onPosterLongClick?.let { cb -> { cb(item) } },
                )
            } else {
                // No width override: the card sizes itself from the user's poster-style
                // setting (and follows catalog landscape mode like home's rows do).
                HomePosterCard(
                    item = item,
                    onClick = { onPosterClick(item) },
                    onLongClick = onPosterLongClick?.let { cb -> { cb(item) } },
                )
            }
        }
    }
}

@Composable
private fun XtreamLiveChannelTile(
    item: MetaPreview,
    epg: ChannelEpg?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    // The card style's natural landscape width — not a hardcoded tile size.
    val tileWidth = landscapePosterWidth(rememberPosterCardStyleUiState().widthDp)
    Column(modifier = Modifier.width(tileWidth)) {
        HomePosterCard(
            item = item,
            useLandscapeBackdropMode = true,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        Text(
            text = epg?.now ?: stringResource(Res.string.compose_iptv_hub_epg_no_information),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = NuvioTokens.Space.s2, start = NuvioTokens.Space.s2, end = NuvioTokens.Space.s2),
        )
        epg?.next?.let { next ->
            Text(
                text = stringResource(Res.string.compose_iptv_hub_epg_next, next),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted.copy(alpha = tokens.opacity.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s2),
            )
        }
    }
}

// --- loading / empty / error states ----------------------------------------------

/** Initial category-list load: home-style shimmer shelves instead of a full-screen spinner. */
@Composable
private fun XtreamHubSkeleton(
    live: Boolean,
    sectionPadding: Dp,
) {
    val tokens = MaterialTheme.nuvio
    val brush = rememberHomeSkeletonBrush()
    Column(
        modifier = Modifier.fillMaxSize().padding(top = NuvioTokens.Space.s4),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
    ) {
        repeat(SKELETON_SHELF_COUNT) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = sectionPadding),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
            ) {
                // Title placeholder (same spec as HomeSkeletonRow).
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                        .background(brush),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
                    repeat(PLACEHOLDER_TILE_COUNT) {
                        XtreamHubTilePlaceholder(live = live, brush = brush)
                    }
                }
            }
        }
    }
}

/**
 * Shimmer silhouette of one tile, mirroring the real card column (image + optional title line +
 * trailing zero-height box with 6dp gaps — see NuvioPosterCard) plus the live tiles' EPG line,
 * all sized from the user's poster style so pending and resolved rows are the same height.
 */
@Composable
private fun XtreamHubTilePlaceholder(
    live: Boolean,
    brush: Brush,
) {
    val style = rememberPosterCardStyleUiState()
    val landscape = live || style.catalogLandscapeModeEnabled
    val cardWidth = if (landscape) landscapePosterWidth(style.widthDp) else style.widthDp.dp
    val cardHeight = if (landscape) landscapePosterHeightForWidth(cardWidth) else style.heightDp.dp
    val cardShape = RoundedCornerShape(style.cornerRadiusDp.dp)
    Column(modifier = Modifier.width(cardWidth)) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .clip(cardShape)
                    .background(brush),
            )
            if (!style.hideLabelsEnabled) {
                // A blank Text with the real title style keeps exactly one line of its height.
                Text(
                    text = " ",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                        .background(brush),
                )
            }
            Box(modifier = Modifier.height(NuvioTokens.Space.none))
        }
        if (live) {
            Text(
                text = " ",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier
                    .padding(top = NuvioTokens.Space.s2, start = NuvioTokens.Space.s2, end = NuvioTokens.Space.s2)
                    .fillMaxWidth(0.5f)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
                    .background(brush),
            )
        }
    }
}

/** Error / empty vocabulary shared with home: NuvioSurfaceCard + primary action. */
@Composable
private fun XtreamHubMessageCard(
    title: String,
    message: String,
    sectionPadding: Dp,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = sectionPadding, vertical = NuvioTokens.Space.s8),
    ) {
        HomeEmptyStateCard(
            title = title,
            message = message,
            actionLabel = actionLabel,
            onActionClick = onAction,
        )
    }
}

@Composable
private fun XtreamHubNoPlaylistState(onAddProvider: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(tokens.colors.background)) {
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        val tabletTopInset = if (maxWidth >= 768.dp) TABLET_TOP_BAR_INSET else 0.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = tabletTopInset)
                .padding(horizontal = sectionPadding, vertical = NuvioTokens.Space.s16),
        ) {
            HomeEmptyStateCard(
                title = stringResource(Res.string.compose_iptv_hub_no_provider_title),
                message = stringResource(Res.string.compose_iptv_hub_no_provider_message),
                actionLabel = stringResource(Res.string.compose_iptv_hub_add_provider),
                onActionClick = onAddProvider,
            )
        }
    }
}

private val TABLET_TOP_BAR_INSET = 72.dp

/**
 * How many rows past the one that just appeared get their items fetched early. Deliberately small:
 * category responses are large, so the win (rows arriving filled in) has to stay cheap. The hard
 * bounds live in XtreamHubRepository, which caps concurrent fetches and drops prefetches once
 * enough are outstanding.
 */
private const val CATEGORY_PREFETCH_LOOKAHEAD = 3
private const val PLACEHOLDER_TILE_COUNT = 6
private const val SKELETON_SHELF_COUNT = 3
private const val ADD_PLAYLIST_OPTION_KEY = "__add_playlist__"
