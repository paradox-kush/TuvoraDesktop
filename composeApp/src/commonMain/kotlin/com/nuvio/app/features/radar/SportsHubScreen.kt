package com.nuvio.app.features.radar

import co.touchlab.kermit.Logger
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.border
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.rememberHomeSkeletonBrush
import com.nuvio.app.features.iptv.XtreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val sportsMatchLog = Logger.withTag("SportsChannelMatch")

/**
 * Sports Centre tab: featured event banners, live & upcoming fixtures for followed leagues,
 * per-league rows, and a browse-with-follow-toggles hierarchy. Tapping a match opens the
 * channel-matching sheet ("which of my channels shows this?"). Works with no IPTV playlist
 * (fixture guide + add-playlist CTA in the sheet).
 */
@Composable
fun SportsHubScreen(
    onPlayChannel: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRecording: (String) -> Unit = {},
) {
    val state by RadarRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { RadarRepository.ensureLoaded() }

    var browseCategory by remember { mutableStateOf<RadarCategory?>(null) }
    var browsing by remember { mutableStateOf(false) }
    var sheetFixture by remember { mutableStateOf<RadarFixture?>(null) }
    // Discovery drill-in: a league/event page listing everything happening in it.
    var leaguePage by remember { mutableStateOf<RadarLeague?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val tabletTopInset = if (maxWidth >= 768.dp) TABLET_TOP_BAR_INSET else 0.dp
        val isWide = maxWidth >= 768.dp
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = tabletTopInset)) {
            when {
                leaguePage != null -> LeagueFixturesPage(
                    state = state,
                    league = leaguePage!!,
                    onBack = { leaguePage = null },
                    onFixtureClick = { sheetFixture = it },
                )
                browsing && isWide -> BrowseTwoPane(
                    state = state,
                    selected = browseCategory,
                    onSelect = { browseCategory = it },
                    onOpenLeague = { leaguePage = it },
                    onBack = { browsing = false; browseCategory = null },
                )
                browsing && browseCategory != null -> BrowseLeagues(
                    state = state,
                    category = browseCategory!!,
                    onOpenLeague = { leaguePage = it },
                    onBack = { browseCategory = null },
                )
                browsing -> BrowseCategories(
                    state = state,
                    onSelect = { browseCategory = it },
                    onBack = { browsing = false },
                )
                else -> SportsOverview(
                    state = state,
                    onOpenBrowse = { browsing = true },
                    onOpenCategory = { browsing = true; browseCategory = it },
                    onOpenLeague = { leaguePage = it },
                    onFixtureClick = { sheetFixture = it },
                )
            }
        }
    }

    sheetFixture?.let { fixture ->
        MatchChannelsSheet(
            fixture = fixture,
            league = fixture.leagueId?.let { state.leagueById(it) },
            isLive = state.isLive(fixture, RadarTime.nowMs()),
            onPlayChannel = onPlayChannel,
            onAddPlaylist = onAddPlaylist,
            onDismiss = { sheetFixture = null },
            onOpenRecording = onOpenRecording,
        )
    }
}

// --- overview (the tab's main scroll) -----------------------------------------

@Composable
private fun SportsOverview(
    state: RadarUiState,
    onOpenBrowse: () -> Unit,
    onOpenCategory: (RadarCategory) -> Unit,
    onOpenLeague: (RadarLeague) -> Unit,
    onFixtureClick: (RadarFixture) -> Unit,
) {
    val nowMs = RadarTime.nowMs()
    // null = closed, "" = choosing a sport, else the chosen sport.
    var addLeagueSport by remember { mutableStateOf<String?>(null) }
    var addingTeam by remember { mutableStateOf(false) }
    val featured = state.activeFeatured(nowMs)
    // Followed clubs feed the same Live & Upcoming row as followed leagues — someone who
    // follows only Arsenal still expects their match at the top, not buried under Browse.
    val upcoming = remember(state, nowMs) {
        val leagueFixtures = state.upcoming(
            leagueIds = state.followedLeagueIds + featured.map { it.leagueId },
            nowMs = nowMs,
        )
        val teamFixtures = state.upcomingForTeams(state.followedTeamIds, nowMs)
        (leagueFixtures + teamFixtures)
            .distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
            .sortedBy { it.startEpochMs }
    }
    // Row lists are pre-filtered: with spacedBy on the LazyColumn an item that renders
    // nothing would still contribute a stray gap.
    val teamRows = remember(state, nowMs) {
        state.followedTeams.mapNotNull { team ->
            state.upcomingForTeams(listOf(team.id), nowMs, cap = 12)
                .takeIf { it.isNotEmpty() }?.let { team to it }
        }
    }
    val leagueRows = remember(state, nowMs) {
        state.follows.mapNotNull { follow ->
            val league = state.leagueById(follow.leagueId) ?: return@mapNotNull null
            state.upcoming(listOf(league.id), nowMs, cap = 12)
                .takeIf { it.isNotEmpty() }?.let { league to it }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        val tokens = MaterialTheme.nuvio
        val rowPadding = PaddingValues(horizontal = sectionPadding)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
            contentPadding = PaddingValues(
                top = NuvioTokens.Space.s4,
                bottom = nuvioSafeBottomPadding(tokens.spacing.screenBottom),
            ),
        ) {
            if (featured.isNotEmpty()) {
                item(key = "featured") {
                    NuvioShelfSection(
                        title = "Featured Events",
                        entries = featured,
                        headerHorizontalPadding = sectionPadding,
                        rowContentPadding = rowPadding,
                        viewAllPillSize = NuvioViewAllPillSize.Compact,
                        key = { it.id },
                    ) { event ->
                        val fixtures = state.fixturesByLeague[event.leagueId].orEmpty()
                        FeaturedBannerCard(
                            event = event,
                            matchCount = fixtures.count { (it.startEpochMs ?: 0) >= nowMs - 4 * 60 * 60 * 1000L },
                            onClick = {
                                // Into the event: every match, live + recent — discovery first.
                                state.leagueById(event.leagueId)?.let(onOpenLeague)
                            },
                        )
                    }
                }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "upcoming") {
                    NuvioShelfSection(
                        title = "Live & Upcoming",
                        entries = upcoming,
                        headerHorizontalPadding = sectionPadding,
                        rowContentPadding = rowPadding,
                        viewAllPillSize = NuvioViewAllPillSize.Compact,
                        key = { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" },
                    ) { fx ->
                        MatchCard(
                            fx,
                            live = state.isLive(fx, nowMs),
                            onClick = { onFixtureClick(fx) },
                            liveScore = fx.id?.let { state.liveScores[it] },
                        )
                    }
                }
            } else if (state.loadingFixtures && (state.follows.isNotEmpty() || featured.isNotEmpty())) {
                item(key = "loading") {
                    MatchRowSkeleton(sectionPadding = sectionPadding)
                }
            }
            items(teamRows, key = { "team-${it.first.id}" }) { (team, fixtures) ->
                NuvioShelfSection(
                    title = team.name,
                    entries = fixtures,
                    headerHorizontalPadding = sectionPadding,
                    rowContentPadding = rowPadding,
                    viewAllPillSize = NuvioViewAllPillSize.Compact,
                    headerLeading = team.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                        { ShelfHeaderBadge(badge) }
                    },
                    key = { "team-${team.id}-${it.id ?: it.hashCode()}" },
                ) { fx ->
                    MatchCard(
                        fx,
                        live = state.isLive(fx, nowMs),
                        onClick = { onFixtureClick(fx) },
                        liveScore = fx.id?.let { state.liveScores[it] },
                    )
                }
            }
            if (state.follows.isEmpty() && state.teamFollows.isEmpty()) {
                item(key = "follow-cta") { FollowCta(onOpenBrowse) }
            } else {
                items(leagueRows, key = { "league-${it.first.id}" }) { (league, fixtures) ->
                    NuvioShelfSection(
                        title = league.name,
                        entries = fixtures,
                        headerHorizontalPadding = sectionPadding,
                        rowContentPadding = rowPadding,
                        viewAllPillSize = NuvioViewAllPillSize.Compact,
                        onViewAllClick = { onOpenLeague(league) },
                        headerLeading = league.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                            { ShelfHeaderBadge(badge) }
                        },
                        key = { "league-${league.id}-${it.id ?: it.hashCode()}" },
                    ) { fx ->
                        MatchCard(
                            fx,
                            live = state.isLive(fx, nowMs),
                            onClick = { onFixtureClick(fx) },
                            liveScore = fx.id?.let { state.liveScores[it] },
                        )
                    }
                }
            }
            item(key = "browse") {
                // Sport tiles + the add-your-own escape hatches, as one shelf. The add tiles sit
                // last so the popular sports stay first.
                val browseEntries: List<Any> =
                    state.catalog.categories + listOf(AddLeagueTileMarker, AddTeamTileMarker)
                NuvioShelfSection(
                    title = "Browse sports",
                    entries = browseEntries,
                    headerHorizontalPadding = sectionPadding,
                    rowContentPadding = rowPadding,
                    key = { entry ->
                        when (entry) {
                            is RadarCategory -> "cat-${entry.name}"
                            AddLeagueTileMarker -> "add-league"
                            else -> "add-team"
                        }
                    },
                ) { entry ->
                    when (entry) {
                        is RadarCategory -> {
                            val followedCount = entry.leagues.count { it.id in state.followedLeagueIds }
                            SportTile(
                                badge = entry.leagues.firstOrNull()?.badge,
                                name = entry.name,
                                subtitle = if (followedCount > 0) "$followedCount followed"
                                else "${entry.leagues.size} to track",
                                // Straight into the sport that was tapped — never the generic
                                // "pick a sport" list of these very same categories.
                                onClick = { onOpenCategory(entry) },
                            )
                        }
                        AddLeagueTileMarker -> AddTile(
                            name = "Add a league",
                            subtitle = if (state.customLeagues.isEmpty()) "Search any sport"
                            else "${state.customLeagues.size} added",
                            onClick = { addLeagueSport = "" },
                        )
                        else -> AddTile(
                            name = "Add a team",
                            subtitle = if (state.teamFollows.isEmpty()) "Follow your club"
                            else "${state.teamFollows.size} followed",
                            onClick = { addingTeam = true },
                        )
                    }
                }
            }
        }
    }

    if (addingTeam) {
        AddTeamSheet(
            followedTeamIds = state.followedTeamIds,
            onDismiss = { addingTeam = false },
        )
    }

    AddLeagueSheets(
        sportOrEmpty = addLeagueSport,
        followedLeagueIds = state.followedLeagueIds,
        onDismiss = { addLeagueSport = null },
        onPickSport = { addLeagueSport = it },
    )
}

@Composable
private fun FollowCta(onOpenBrowse: () -> Unit) {
    // HomeEmptyStateCard pattern: NuvioSurfaceCard + NuvioPrimaryButton.
    NuvioSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
    ) {
        Text(
            "Follow your sports",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        Text(
            "Pick leagues and events to follow — upcoming matches show up here, and Tuvora finds which of your channels is showing them.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s16))
        NuvioPrimaryButton(text = "Browse sports", onClick = onOpenBrowse)
    }
}

// --- browse -------------------------------------------------------------------

@Composable
private fun BrowseHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                // Explicit token color — LocalContentColor defaults to black on the dark bg here.
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BrowseCategories(state: RadarUiState, onSelect: (RadarCategory) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BrowseHeader("Pick a sport", onBack)
        Text(
            "Track the leagues and events you care about. They'll appear on the Sports tab when they're coming up — tap one to find which of your channels is showing it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16),
        )
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        LazyColumn(contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s24))) {
            items(state.catalog.categories, key = { it.name }) { category ->
                CategoryRowItem(
                    category = category,
                    followedCount = category.leagues.count { it.id in state.followedLeagueIds },
                    onClick = { onSelect(category) },
                )
            }
        }
    }
}

@Composable
private fun BrowseLeagues(
    state: RadarUiState,
    category: RadarCategory,
    onOpenLeague: (RadarLeague) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BrowseHeader(category.name, onBack)
        LeagueToggleList(state, category, onOpenLeague)
    }
}

@Composable
private fun BrowseTwoPane(
    state: RadarUiState,
    selected: RadarCategory?,
    onSelect: (RadarCategory?) -> Unit,
    onOpenLeague: (RadarLeague) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BrowseHeader("Follow your sports", onBack)
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(320.dp), contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s24))) {
                items(state.catalog.categories, key = { it.name }) { category ->
                    CategoryRowItem(
                        category = category,
                        followedCount = category.leagues.count { it.id in state.followedLeagueIds },
                        selected = category.name == selected?.name,
                        onClick = { onSelect(category) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                val category = selected ?: state.catalog.categories.firstOrNull()
                if (category != null) LeagueToggleList(state, category, onOpenLeague)
            }
        }
    }
}

@Composable
private fun LeagueToggleList(
    state: RadarUiState,
    category: RadarCategory,
    onOpenLeague: (RadarLeague) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s24))) {
        items(category.leagues, key = { it.id }) { league ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Tap = go INSIDE the league (discovery); the switch is just for following.
                    .clickable { onOpenLeague(league) }
                    .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = league.badge,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(NuvioTokens.Space.s12))
                Column(Modifier.weight(1f)) {
                    Text(league.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    league.sport?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(
                    checked = league.id in state.followedLeagueIds,
                    onCheckedChange = { RadarRepository.toggleFollow(league) },
                    // Settings' switch palette (accent track) — never default M3 colors.
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.nuvio.colors.onAccent,
                        checkedTrackColor = MaterialTheme.nuvio.colors.accent,
                        uncheckedThumbColor = MaterialTheme.nuvio.colors.textMuted,
                        uncheckedTrackColor = MaterialTheme.nuvio.colors.borderDefault,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CategoryRowItem(
    category: RadarCategory,
    followedCount: Int,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                else Modifier
            )
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Artwork-first like the rest of the app: the category's flagship league badge.
        AsyncImage(
            model = category.leagues.firstOrNull()?.badge,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(NuvioTokens.Space.s12))
        Column(Modifier.weight(1f)) {
            Text(category.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (followedCount > 0) "$followedCount followed · ${category.leagues.size} to track"
                else "${category.leagues.size} to track",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- league / event page (discovery drill-in) -----------------------------------

@Composable
private fun LeagueFixturesPage(
    state: RadarUiState,
    league: RadarLeague,
    onBack: () -> Unit,
    onFixtureClick: (RadarFixture) -> Unit,
) {
    // Browsing must not require following — fetch this league on demand.
    LaunchedEffect(league.id) { RadarRepository.ensureLeagueLoaded(league.id) }
    val nowMs = RadarTime.nowMs()
    val upcoming = state.upcoming(listOf(league.id), nowMs, cap = 40)
    val recent = state.recent(league.id, nowMs)
    val loaded = state.fixturesByLeague.containsKey(league.id)

    Column(Modifier.fillMaxSize()) {
        BrowseHeader(league.name, onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s24))) {
            item(key = "league-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(model = league.badge, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(NuvioTokens.Space.s12))
                    Column(Modifier.weight(1f)) {
                        league.sport?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (upcoming.any { state.isLive(it, nowMs) }) "Live now" else "${upcoming.size} upcoming",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        if (league.id in state.followedLeagueIds) "Following" else "Follow",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = league.id in state.followedLeagueIds,
                        onCheckedChange = { RadarRepository.toggleFollow(league) },
                        modifier = Modifier.padding(start = NuvioTokens.Space.s8),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.nuvio.colors.onAccent,
                            checkedTrackColor = MaterialTheme.nuvio.colors.accent,
                            uncheckedThumbColor = MaterialTheme.nuvio.colors.textMuted,
                            uncheckedTrackColor = MaterialTheme.nuvio.colors.borderDefault,
                        ),
                    )
                }
            }
            if (!loaded) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "up-title") { SectionTitle("Live & Upcoming") }
                items(upcoming, key = { "up-${it.id ?: it.hashCode()}" }) { fx ->
                    MatchCard(
                        fx,
                        live = state.isLive(fx, nowMs),
                        onClick = { onFixtureClick(fx) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
                        liveScore = fx.id?.let { state.liveScores[it] },
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent-title") { SectionTitle("Recent results") }
                items(recent, key = { "rec-${it.id ?: it.hashCode()}" }) { fx ->
                    MatchCard(
                        fx,
                        live = false,
                        onClick = { onFixtureClick(fx) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
                    )
                }
            }
            if (loaded && upcoming.isEmpty() && recent.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No scheduled matches right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(NuvioTokens.Space.s16),
                    )
                }
            }
        }
    }
}

// --- cards ---------------------------------------------------------------------

@Composable
private fun FeaturedBannerCard(event: RadarFeaturedEvent, matchCount: Int, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .width(300.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(NuvioTokens.Space.s12))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = event.banner ?: event.badge,
            contentDescription = event.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(NuvioTokens.Space.s10)) {
            Text(
                event.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (matchCount > 0) "$matchCount upcoming" else "${event.from} – ${event.to}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

internal val MatchCardWidth = 280.dp
private val MatchCardTeamsMinHeight = 64.dp
private val MatchPillShape = RoundedCornerShape(percent = 50)

private object AddLeagueTileMarker
private object AddTeamTileMarker

/**
 * A fixture card in the home/IPTV shelf vocabulary: league line + status pill on top,
 * one row per team with its crest and (live/final) score, kickoff + venue below. Works
 * both as a fixed-width rail tile and stretched fillMaxWidth in the league page list.
 */
@Composable
internal fun MatchCard(
    fixture: RadarFixture,
    live: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(MatchCardWidth),
    liveScore: RadarLiveScore? = null,
) {
    val tokens = MaterialTheme.nuvio
    val cardShape = RoundedCornerShape(NuvioTokens.Radius.xl)
    Column(
        modifier = modifier
            .clip(cardShape)
            .background(tokens.colors.surface)
            // Hairline so the card never vanishes into AMOLED pure-black backgrounds.
            .border(1.dp, tokens.colors.borderSubtle, cardShape)
            .clickable(onClick = onClick)
            .padding(NuvioTokens.Space.s14),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            fixture.leagueBadge?.takeIf { it.isNotBlank() }?.let { badge ->
                AsyncImage(
                    model = badge,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(NuvioTokens.Space.s16),
                )
                Spacer(Modifier.width(NuvioTokens.Space.s6))
            }
            Text(
                fixture.roundLabel ?: fixture.league ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(NuvioTokens.Space.s8))
            MatchStatusPill(fixture, live, liveScore)
        }
        Spacer(Modifier.height(NuvioTokens.Space.s10))
        val home = fixture.home?.takeIf { it.isNotBlank() }
        val away = fixture.away?.takeIf { it.isNotBlank() }
        Column(Modifier.heightIn(min = MatchCardTeamsMinHeight)) {
            if (home != null && away != null) {
                val homeScore = (liveScore?.homeScore ?: fixture.homeScore)?.takeIf { it.isNotBlank() }
                val awayScore = (liveScore?.awayScore ?: fixture.awayScore)?.takeIf { it.isNotBlank() }
                TeamRow(home, fixture.homeBadge, homeScore, dimScore = scoreTrails(homeScore, awayScore))
                TeamRow(away, fixture.awayBadge, awayScore, dimScore = scoreTrails(awayScore, homeScore))
            } else {
                // Motorsport/golf-style events have no team pair — the event name carries the card.
                Text(
                    fixture.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(NuvioTokens.Space.s10))
        val startMs = fixture.startEpochMs
        val whenLabel = when {
            live -> null
            startMs != null -> radarWhenLabel(startMs)
            else -> "Time TBC"
        }
        val hot = !live && startMs != null &&
            RadarTime.dayLabel(startMs).let { it == "Today" || it == "Tomorrow" }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 18.dp),
        ) {
            if (whenLabel != null) {
                Text(
                    whenLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hot) tokens.colors.accent else tokens.colors.textSecondary,
                    maxLines = 1,
                )
            }
            fixture.venue?.takeIf { it.isNotBlank() }?.let { venue ->
                if (whenLabel != null) {
                    Text(
                        " · ",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                    )
                }
                Text(
                    venue,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun TeamRow(name: String, badge: String?, score: String?, dimScore: Boolean) {
    val tokens = MaterialTheme.nuvio
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s2),
    ) {
        TeamBadge(name = name, badge = badge)
        Spacer(Modifier.width(NuvioTokens.Space.s10))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!score.isNullOrBlank()) {
            Spacer(Modifier.width(NuvioTokens.Space.s8))
            Text(
                score,
                style = MaterialTheme.typography.titleMedium,
                color = if (dimScore) tokens.colors.textMuted else tokens.colors.textPrimary,
                maxLines = 1,
            )
        }
    }
}

/** Team crest with a monogram-circle fallback so badge-less teams never leave a hole. */
@Composable
private fun TeamBadge(name: String, badge: String?) {
    val tokens = MaterialTheme.nuvio
    val failed = remember(badge) { mutableStateOf(false) }
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (!badge.isNullOrBlank() && !failed.value) {
            AsyncImage(
                model = badge,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onState = { state -> if (state is AsyncImagePainter.State.Error) failed.value = true },
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(tokens.colors.surfaceElevated)
                    .border(1.dp, tokens.colors.borderSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    teamMonogram(name),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}

private fun teamMonogram(name: String): String {
    val words = name.split(' ', '-').filter { it.firstOrNull()?.isLetter() == true }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
        words.isNotEmpty() -> words[0].take(2).uppercase()
        else -> "?"
    }
}

/** True when both scores parse as numbers and this side is behind — the trailing score dims. */
private fun scoreTrails(own: String?, other: String?): Boolean {
    val a = own?.trim()?.toIntOrNull() ?: return false
    val b = other?.trim()?.toIntOrNull() ?: return false
    return a < b
}

@Composable
private fun MatchStatusPill(fixture: RadarFixture, live: Boolean, liveScore: RadarLiveScore?) {
    val tokens = MaterialTheme.nuvio
    when {
        live -> LiveBadge(progress = liveScore?.progress)
        fixture.postponed == "yes" -> StatusPill("POSTPONED", tokens.colors.textMuted)
        fixture.scoreLabel != null -> StatusPill("FT", tokens.colors.textMuted)
        else -> {
            val day = fixture.startEpochMs?.let { RadarTime.dayLabel(it) }
            if (day == "Today" || day == "Tomorrow") StatusPill(day.uppercase(), tokens.colors.accent)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(MatchPillShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), MatchPillShape)
            .padding(horizontal = NuvioTokens.Space.s8, vertical = 2.dp),
    )
}

@Composable
internal fun LiveBadge(progress: String? = null) {
    val danger = MaterialTheme.nuvio.colors.danger
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MatchPillShape)
            .background(danger.copy(alpha = 0.14f))
            .border(1.dp, danger.copy(alpha = 0.45f), MatchPillShape)
            .padding(horizontal = NuvioTokens.Space.s8, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(danger))
        Spacer(Modifier.width(4.dp))
        Text(
            if (progress.isNullOrBlank()) "LIVE" else "LIVE $progress",
            style = MaterialTheme.typography.labelSmall,
            color = danger,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Small league/team badge next to a shelf header title. */
@Composable
private fun ShelfHeaderBadge(badge: String) {
    AsyncImage(
        model = badge,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun SportTile(badge: String?, name: String, subtitle: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(shape)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(NuvioTokens.Space.s14),
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (!badge.isNullOrBlank()) {
                AsyncImage(
                    model = badge,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(tokens.colors.surfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        teamMonogram(name),
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddTile(name: String, subtitle: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(shape)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(NuvioTokens.Space.s14),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(tokens.colors.surfaceElevated)
                .border(1.dp, tokens.colors.borderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textSecondary)
        }
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shimmer stand-in for a match shelf while first fixtures load — real header-sized bar + cards. */
@Composable
private fun MatchRowSkeleton(sectionPadding: Dp) {
    val brush = rememberHomeSkeletonBrush()
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
        Box(
            Modifier
                .padding(horizontal = sectionPadding)
                .width(140.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush),
        )
        Row(
            modifier = Modifier.padding(horizontal = sectionPadding),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
        ) {
            repeat(3) {
                Box(
                    Modifier
                        .width(MatchCardWidth)
                        .height(132.dp)
                        .clip(RoundedCornerShape(NuvioTokens.Radius.xl))
                        .background(brush),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onClick != null) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- match sheet -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatchChannelsSheet(
    fixture: RadarFixture,
    league: RadarLeague?,
    isLive: Boolean,
    onPlayChannel: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    onDismiss: () -> Unit,
    onOpenRecording: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val xtreamState by XtreamRepository.uiState.collectAsStateWithLifecycle()
    var matches by remember(fixture) { mutableStateOf<List<RadarChannelMatcher.ChannelMatch>>(emptyList()) }
    var recordings by remember(fixture) { mutableStateOf<List<RadarChannelMatcher.RecordingHit>>(emptyList()) }
    var matching by remember(fixture) { mutableStateOf(true) }
    var matchingFailed by remember(fixture) { mutableStateOf(false) }
    val hasPlaylists = xtreamState.accounts.any { it.enabled }
    val fixtureStarted = (fixture.startEpochMs ?: Long.MAX_VALUE) <= RadarTime.nowMs()

    LaunchedEffect(fixture) {
        try {
            XtreamRepository.ensureLoaded()
            if (XtreamRepository.uiState.value.accounts.any { it.enabled }) {
                // Recordings only make sense once the match has started; probe alongside channels.
                if (fixtureStarted) {
                    launch {
                        val found = try {
                            withContext(Dispatchers.Default) { RadarChannelMatcher.findRecordings(fixture) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            sportsMatchLog.w(error) { "Sports recording lookup failed" }
                            emptyList()
                        }
                        // This launch inherits LaunchedEffect's Main dispatcher.
                        recordings = found
                    }
                }
                // Broadcaster listings are one cached edge-fn call; bounded so a slow network
                // can't hold the sheet hostage (matching proceeds without them).
                val stations = withContext(Dispatchers.Default) {
                    try {
                        withTimeoutOrNull(4_000) { RadarRepository.tvStations(fixture.id) } ?: emptyList()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        sportsMatchLog.w(error) { "Sports broadcaster lookup failed" }
                        emptyList()
                    }
                }
                val result = RadarChannelMatcher.match(fixture, league, stations, onPartial = { partial ->
                    withContext(Dispatchers.Main) { matches = partial }
                })
                // match() returns to LaunchedEffect's Main dispatcher before Compose state changes.
                matches = result
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sportsMatchLog.e(error) { "Sports channel matching failed" }
            matchingFailed = true
        } finally {
            matching = false
        }
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = NuvioTokens.Space.s16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fixture.displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (isLive) LiveBadge()
            }
            Text(
                listOfNotNull(
                    fixture.roundLabel ?: fixture.league,
                    fixture.startEpochMs?.let { radarWhenLabel(it) },
                    fixture.venue,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(NuvioTokens.Space.s12))
            if (recordings.isNotEmpty()) {
                Text(
                    "RECORDINGS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NuvioTokens.Space.s6))
                recordings.forEach { rec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onOpenRecording(rec.contentId)
                            }
                            .padding(vertical = NuvioTokens.Space.s8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = rec.poster,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(NuvioTokens.Space.s6)),
                        )
                        Spacer(Modifier.width(NuvioTokens.Space.s12))
                        Column(Modifier.weight(1f)) {
                            Text(
                                rec.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                rec.playlistName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(NuvioTokens.Space.s8))
            }
            Text(
                "CHANNELS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(NuvioTokens.Space.s6))
            when {
                !hasPlaylists -> {
                    Text(
                        "Add an IPTV playlist to find and watch this match on your channels.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onDismiss(); onAddPlaylist() }) { Text("Add IPTV playlist") }
                }
                matches.isEmpty() && matching -> Box(
                    Modifier.fillMaxWidth().height(80.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(strokeWidth = 2.dp) }
                matches.isEmpty() && matchingFailed -> Text(
                    "Couldn't load channels from your providers. Please try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                matches.isEmpty() -> Text(
                    "None of your channels list this match. Matching depends on your playlist's EPG and channel names.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn {
                    items(matches, key = { it.channel.contentId }) { match ->
                        // Started/finished + archived channel -> catch-up Replay of the programme.
                        val replayId = if (fixtureStarted) RadarChannelMatcher.replayFor(match, fixture) else null
                        ChannelMatchRow(
                            match = match,
                            onReplay = replayId?.let { id ->
                                {
                                    onDismiss()
                                    onPlayChannel(id)
                                }
                            },
                        ) {
                            RadarChannelMatcher.ensurePlayable(match)
                            onDismiss()
                            onPlayChannel(match.channel.contentId)
                        }
                    }
                    if (matching) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(NuvioTokens.Space.s8), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(NuvioTokens.Space.s24))
        }
    }
}

@Composable
private fun ChannelMatchRow(
    match: RadarChannelMatcher.ChannelMatch,
    onReplay: (() -> Unit)? = null,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = match.channel.logo,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(NuvioTokens.Space.s6)),
        )
        Spacer(Modifier.width(NuvioTokens.Space.s12))
        Column(Modifier.weight(1f)) {
            Text(
                match.channel.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val programme = match.programme
            Text(
                when {
                    programme != null -> listOfNotNull(
                        match.language,
                        "${programme.title} · ${RadarTime.formatTime(programme.startMs)} – ${RadarTime.formatTime(programme.endMs)}",
                    ).joinToString(" · ")
                    match.via == RadarChannelMatcher.MatchVia.LISTING -> listOfNotNull(
                        match.language,
                        "TV listing",
                        match.channel.playlistName,
                    ).joinToString(" · ")
                    else -> match.channel.playlistName
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onReplay != null) {
            TextButton(onClick = onReplay, contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s8)) {
                Text("↩ Replay", style = MaterialTheme.typography.labelMedium)
            }
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
    }
}


private val TABLET_TOP_BAR_INSET = 72.dp

/**
 * Sport -> country -> league, for leagues outside the published catalog. Sport comes first
 * because a country alone mixes every sport together, and the discovery endpoint filters on
 * both. Anything followed here lands on this account only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLeagueSheets(
    sportOrEmpty: String?,
    followedLeagueIds: Set<String>,
    onDismiss: () -> Unit,
    onPickSport: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Keyed on the sport: this composable stays in composition after the sheet closes (the
    // early return below is what hides it), so unkeyed remembers survived into the next
    // add-a-league flow and showed the previous sport's country and results.
    var country by remember(sportOrEmpty) { mutableStateOf<String?>(null) }
    var loading by remember(sportOrEmpty) { mutableStateOf(false) }
    var results by remember(sportOrEmpty) { mutableStateOf<List<RadarLeague>>(emptyList()) }
    // Search runs beside the sport/country drill-down rather than inside it: someone who knows
    // the league's name shouldn't have to guess which country the catalog files it under.
    var query by remember(sportOrEmpty) { mutableStateOf("") }
    var searching by remember(sportOrEmpty) { mutableStateOf(false) }
    var searchResults by remember(sportOrEmpty) { mutableStateOf<List<RadarLeague>>(emptyList()) }

    val trimmedQuery = query.trim()
    // Debounced so a settled query costs one upstream call, not one per keystroke.
    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery.length < MIN_LEAGUE_QUERY) {
            searching = false
            searchResults = emptyList()
            return@LaunchedEffect
        }
        searching = true
        delay(SEARCH_DEBOUNCE_MS)
        searchResults = RadarCatalogClient.searchLeagues(text = trimmedQuery)
        searching = false
    }

    if (sportOrEmpty == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val chosenCountry = country
        // ONE call site, deliberately outside the `when` below. Nesting it in the branches gave
        // each branch its own TextField, so typing the first character switched branch, disposed
        // the field and recreated it — which dropped focus and closed the keyboard mid-word.
        LeagueSearchField(query = query, onQueryChange = { query = it })
        when {
            // A non-empty query takes over the sheet at any depth — it is the faster route in.
            trimmedQuery.isNotEmpty() -> {
                LeagueResultList(
                    leagues = searchResults,
                    followedLeagueIds = followedLeagueIds,
                    loading = searching,
                    emptyText = if (trimmedQuery.length < MIN_LEAGUE_QUERY) {
                        "Keep typing to search leagues."
                    } else {
                        "No leagues match \"$trimmedQuery\"."
                    },
                )
            }
            sportOrEmpty.isEmpty() -> {
                SectionTitle("Or pick a sport")
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(RADAR_LEAGUE_SPORTS, key = { it }) { sport ->
                        SimplePickRow(sport) { onPickSport(sport) }
                    }
                }
            }
            chosenCountry == null -> {
                SectionTitle(sportOrEmpty)
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    items(RADAR_LEAGUE_COUNTRIES, key = { it }) { c ->
                        SimplePickRow(c) {
                            country = c
                            loading = true
                            scope.launch {
                                results = RadarCatalogClient.searchLeagues(country = c, sport = sportOrEmpty)
                                loading = false
                            }
                        }
                    }
                }
            }
            else -> {
                SectionTitle("$sportOrEmpty · $chosenCountry")
                LeagueResultList(
                    leagues = results,
                    followedLeagueIds = followedLeagueIds,
                    loading = loading,
                    emptyText = "No leagues found for $sportOrEmpty in $chosenCountry.",
                )
            }
        }
    }
}

/** Shortest query worth a round trip — one or two letters match half the database. */
private const val MIN_LEAGUE_QUERY = 3
private const val SEARCH_DEBOUNCE_MS = 350L

@Composable
private fun LeagueSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search leagues",
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
    )
}

/** The follow list shared by the country drill-down and the search results. */
@Composable
private fun LeagueResultList(
    leagues: List<RadarLeague>,
    followedLeagueIds: Set<String>,
    loading: Boolean,
    emptyText: String,
) {
    // One call site for the hint, so switching between "loading" and "empty" doesn't churn the
    // node next to the search field.
    val hint = when {
        loading -> "Finding leagues…"
        leagues.isEmpty() -> emptyText
        else -> null
    }
    if (hint != null) {
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(NuvioTokens.Space.s16),
        )
    }
    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
        items(leagues, key = { it.id }) { league ->
            val followed = league.id in followedLeagueIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { RadarRepository.toggleFollow(league) }
                    .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = league.badge,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(NuvioTokens.Space.s12))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        league.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Search spans every country, so the sport is what tells two similarly
                    // named leagues apart.
                    league.sport?.takeIf { it.isNotBlank() }?.let { sport ->
                        Text(
                            sport,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    if (followed) "Following" else "Follow",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (followed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Follow a single club. Search-only by design: nobody finds their team by scrolling a list of
 * every club in a country, and unlike leagues there is no curated set to browse in the first
 * place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTeamSheet(
    followedTeamIds: Set<String>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<RadarTeam>>(emptyList()) }

    val trimmedQuery = query.trim()
    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery.length < MIN_LEAGUE_QUERY) {
            searching = false
            results = emptyList()
            return@LaunchedEffect
        }
        searching = true
        delay(SEARCH_DEBOUNCE_MS)
        results = RadarCatalogClient.searchTeams(trimmedQuery)
        searching = false
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SectionTitle("Follow a team")
        LeagueSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = "Search teams",
        )
        val hint = when {
            searching -> "Finding teams…"
            results.isNotEmpty() -> null
            trimmedQuery.length < MIN_LEAGUE_QUERY -> "Type a club's name to find it."
            else -> "No teams match \"$trimmedQuery\"."
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(NuvioTokens.Space.s16),
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
            items(results, key = { it.id }) { team ->
                val followed = team.id in followedTeamIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { RadarRepository.toggleFollowTeam(team) }
                        .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = team.badge,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(NuvioTokens.Space.s12))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            team.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // Many clubs share a short name; the league is what separates them.
                        listOfNotNull(team.league, team.country)
                            .firstOrNull { it.isNotBlank() }
                            ?.let { subtitle ->
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                    Text(
                        if (followed) "Following" else "Follow",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (followed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SimplePickRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text("\u203A", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

