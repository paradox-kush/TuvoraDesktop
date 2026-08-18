package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape

/** Top-level IPTV hub state. Live is handled by its own guide (P5); this covers VOD + Series browse. */
data class XtreamHubUiState(
    val accounts: List<XtreamAccount> = emptyList(),
    /**
     * False until ensureLoaded has actually read the stored accounts. The first composition runs
     * BEFORE the screen's LaunchedEffect, so an empty [accounts] alone cannot mean "no playlists" —
     * gating the add-a-playlist empty state on this stops it flashing over a populated hub
     * (field-reported: visible for a second when the process was busy).
     */
    val accountsLoaded: Boolean = false,
    val selectedAccountId: String? = null,
    val section: XtreamHubSection = XtreamHubSection.LIVE,
    val categories: List<XtreamHubCategory> = emptyList(),
    val loadingCategories: Boolean = false,
    // Non-null when the category-list fetch failed and there was no cache to fall back on — the UI
    // shows this instead of spinning forever (dead portal / Cloudflare block / timeout). Carries
    // WHICH of those it was, so the card can stop telling every viewer the portal is down.
    val loadError: IptvLoadFailurePolicy.Failure? = null,
)

enum class XtreamHubSection { LIVE, MOVIES, SERIES }

/** The account.contentTypes / categorySelections key this hub section corresponds to. */
val XtreamHubSection.contentKey: String
    get() = when (this) {
        XtreamHubSection.LIVE -> CONTENT_TYPE_LIVE
        XtreamHubSection.MOVIES -> CONTENT_TYPE_MOVIES
        XtreamHubSection.SERIES -> CONTENT_TYPE_SERIES
    }

/** Now/next program titles for a live channel (from get_short_epg). */
data class ChannelEpg(val now: String?, val next: String?)

data class XtreamHubCategory(
    val id: String,
    val name: String,
    val items: List<MetaPreview> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** More rows exist past [items] (item 5): the row's end-trigger calls loadMore. */
    val hasMore: Boolean = false,
)

fun XtreamMovie.toMetaPreview(accountId: String): MetaPreview = MetaPreview(
    id = XtreamItemRegistry.vodId(accountId, streamId),
    type = "movie",
    name = name,
    poster = poster,
    posterShape = PosterShape.Poster,
)

fun XtreamSeriesItem.toMetaPreview(accountId: String): MetaPreview = MetaPreview(
    id = XtreamItemRegistry.seriesId(accountId, seriesId),
    type = "series",
    name = name,
    poster = poster,
    posterShape = PosterShape.Poster,
)

fun XtreamChannel.toMetaPreview(accountId: String): MetaPreview = MetaPreview(
    id = XtreamItemRegistry.liveId(accountId, streamId),
    type = "tv",
    name = name,
    poster = logo,
    logo = logo,
    posterShape = PosterShape.Landscape,
)
