package com.nuvio.app.features.iptv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.epg.EpgMirrorRepository
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import kotlinx.coroutines.launch

/**
 * Which playlist (and, on the checklist sub-page, which content type) the "Content & Categories"
 * settings pages edit — set right before navigating. `type` is null on the type-list page and holds
 * the drilled-into content type on the category-checklist page.
 */
internal object XtreamContentPage {
    var accountId: String? = null
        private set
    var type: String? = null
        private set

    fun open(id: String) {
        accountId = id
        type = null
    }

    /** Drill from the type list into one type's category checklist. */
    fun openChecklist(contentType: String) {
        type = contentType
    }
}

private val TYPE_LABELS = listOf(
    CONTENT_TYPE_LIVE to "Live TV",
    CONTENT_TYPE_MOVIES to "Movies",
    CONTENT_TYPE_SERIES to "Series",
)

private fun labelForType(type: String): String =
    TYPE_LABELS.firstOrNull { it.first == type }?.second ?: type

/** Carded fallback shown if the target playlist vanished (e.g. deleted on another device). */
@Composable
private fun PlaylistGoneCard(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "Content & Categories", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            Text(
                text = "This playlist no longer exists.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                modifier = Modifier.padding(
                    horizontal = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16,
                    vertical = if (isTablet) NuvioTokens.Space.s16 else NuvioTokens.Space.s14,
                ),
            )
        }
    }
}

/**
 * "Content & Categories" for one playlist: three content-type toggle rows (with selected-category
 * counts). Tapping an enabled row drills into that type's category checklist (a separate settings
 * page). Option-only edits — persisted via XtreamRepository.updateOptions (sync-pushed, no re-verify).
 */
internal fun LazyListScope.xtreamContentSettingsContent(
    isTablet: Boolean,
    state: XtreamUiState,
    onOpenType: (String) -> Unit,
) {
    item {
        val account = state.accounts.firstOrNull { it.id == XtreamContentPage.accountId }
        if (account == null) {
            PlaylistGoneCard(isTablet = isTablet)
            return@item
        }

        // One cheap categories call per type (id + name only) so the counts can render.
        var categories by remember(account.id) { mutableStateOf<Map<String, List<XtreamCategory>>>(emptyMap()) }
        var fetchAttempt by remember(account.id) { mutableStateOf(0) }
        LaunchedEffect(account.id, fetchAttempt) {
            val client = IptvClient.forAccount(account)   // xtream -> panel, m3u_url -> content DB
            for ((type, _) in TYPE_LABELS) {
                if (categories[type] != null) continue
                val fetched = when (type) {
                    CONTENT_TYPE_LIVE -> client.liveCategories(account)
                    CONTENT_TYPE_MOVIES -> client.vodCategories(account)
                    else -> client.seriesCategories(account)
                }.getOrNull()
                if (fetched != null) categories = categories + (type to fetched)
            }
        }

        ContentTypeList(
            account = account,
            categories = categories,
            isTablet = isTablet,
            onOpenType = { type ->
                if (categories[type] == null) {
                    // User-driven re-attempt after a failed fetch: clear the panel breaker FIRST
                    // (WP6) so neither this refetch nor the checklist page it opens fast-fails.
                    IptvPanelGuard.resetForAccount(account)
                    fetchAttempt++
                }
                onOpenType(type)
            },
        )

        val supportsCatchUp = CatchUpEpgRepository.supportsCatchUp(account)
        if (supportsCatchUp) {
            CatchUpSettings(account = account, isTablet = isTablet)
        }
        // The Guide section shows for every playlist type: the EPG-source coverage line applies
        // to all of them (the mirror is wired for Xtream, M3U and Stalker alike). The manual
        // offset row stays Xtream-only — it corrects the panel short-EPG lane the others lack.
        GuideSettings(account = account, isTablet = isTablet, showOffsetRow = supportsCatchUp)
    }
}

/**
 * The two catch-up escape hatches, per playlist.
 *
 * Both exist because panels lie in ways detection can't fix. The container preference is the
 * scrub-bar knob — the default TS-first order is iptvnator's field-proven one, and a failing m3u8
 * still walks back to TS, so flipping it costs reliability only on panels that serve HLS badly.
 * The time correction is the one every mature player ships (iptvsimple's `catchup-correction`,
 * TiviMate's per-playlist EPG offset) because a geo-mismatched panel reports a clock that is
 * simply wrong, and no amount of measuring it helps.
 */
@Composable
private fun CatchUpSettings(account: XtreamAccount, isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    val correction = account.catchUpTimeCorrectionMinutes
        .coerceIn(CATCH_UP_CORRECTION_MIN_MINUTES, CATCH_UP_CORRECTION_MAX_MINUTES)

    SettingsSection(title = "Catch-up", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = "Prefer m3u8 (enables scrubbing)",
                description = if (account.catchUpPreferM3u8) {
                    "Asks the panel for HLS first, so replays have a progress bar. Falls back to TS."
                } else {
                    "Asks the panel for TS first — the more reliable default. Replays usually can't scrub."
                },
                checked = account.catchUpPreferM3u8,
                isTablet = isTablet,
                onCheckedChange = { on ->
                    XtreamRepository.updateOptions(account.id) { acc -> acc.copy(catchUpPreferM3u8 = on) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = "Time correction",
                description = "Shifts replay start times when the panel's clock is wrong. " +
                    "Currently ${formatCorrection(correction)}. Tap to step by 30 minutes; " +
                    "wraps back to none past +14 h.",
                isTablet = isTablet,
                onClick = {
                    val next = correction + CORRECTION_STEP_MINUTES
                    val wrapped = if (next > CATCH_UP_CORRECTION_MAX_MINUTES) CATCH_UP_CORRECTION_MIN_MINUTES else next
                    XtreamRepository.updateOptions(account.id) { acc ->
                        acc.copy(catchUpTimeCorrectionMinutes = wrapped)
                    }
                    // The measured offset is cached per session and this rides on top of it.
                    CatchUpEpgRepository.forget(account.id)
                },
                trailingContent = {
                    Text(
                        text = formatCorrection(correction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                    )
                },
            )
        }
        Text(
            text = "Catch-up replays programmes your provider recorded. Only channels the provider " +
                "marks with an archive can be replayed — on most playlists that is a small handful.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
        )
    }
}

/**
 * The guide's per-playlist escape hatch, beside the catch-up ones.
 *
 * Auto-detection ([XtreamEpochSkew]) repairs panels whose EPG epochs are their own wall clock —
 * the measured wa12 lie — but a residue of panels is wrong in ways no measurement reaches, and
 * every mature player ships a manual EPG shift for exactly them (TiviMate's per-playlist EPG
 * offset, XUI's `epg_shift`). Setting it overrides auto; 0 returns to auto, not to "+0".
 */
@Composable
private fun GuideSettings(account: XtreamAccount, isTablet: Boolean, showOffsetRow: Boolean) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val correction = account.guideEpgCorrectionMinutes
        .coerceIn(CATCH_UP_CORRECTION_MIN_MINUTES, CATCH_UP_CORRECTION_MAX_MINUTES)

    // Read-only EPG-source coverage — cheap by construction: one mirror mapping read + the
    // in-memory session tally (+ the stored ingest count where one exists). Never a lineup
    // scan, never a panel call — coverage must not cost what it reports on.
    var coverage by remember(account.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(account.id) {
        coverage = guideEpgCoverageLine(account.id)
    }

    SettingsSection(title = "Guide", isTablet = isTablet) {
        if (showOffsetRow) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Guide EPG offset",
                    description = "Shifts guide times when programmes show at the wrong hour. " +
                        "Auto detects most wrong-clock panels. Currently ${formatGuideOffset(correction)}. " +
                        "Tap to step by 30 minutes; wraps back to Auto past +14 h.",
                    isTablet = isTablet,
                    onClick = {
                        val next = correction + CORRECTION_STEP_MINUTES
                        val wrapped = if (next > CATCH_UP_CORRECTION_MAX_MINUTES) CATCH_UP_CORRECTION_MIN_MINUTES else next
                        XtreamRepository.updateOptions(account.id) { acc ->
                            acc.copy(guideEpgCorrectionMinutes = wrapped)
                        }
                        // Stored guide rows were corrected under the OLD offset and the fetch gate
                        // would keep showing them for hours — open the stamps so the next focus
                        // refetches. Same load-bearing-invalidation shape as the replay row's forget().
                        scope.launch {
                            runCatching { com.nuvio.app.features.iptv.content.IptvContentDb.resetEpgFetchStamps(account.id) }
                        }
                        // Sources measured under the old offset are stale too: a channel that fell
                        // to the mirror because its rows looked skewed deserves a fresh panel ask.
                        EpgSourceLadder.sessionMemory.forgetAccount(account.id)
                    },
                    trailingContent = {
                        Text(
                            text = formatGuideOffset(correction),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textSecondary,
                        )
                    },
                )
            }
        }
        coverage?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
            )
        }
    }
}

/**
 * The guide's EPG-source coverage, per playlist: how far the backup guide (the mirrored canonical
 * EPG) reaches, and which rung has actually fed each channel browsed this session. The mapped
 * count is the mirror's streamId→epgId table for this playlist (one indexed read); the total is
 * the stored ingest count where one exists (M3U/Stalker) — Xtream lineups aren't ingested, so
 * their line shows the mapped figure alone rather than paying a panel call to count.
 */
private suspend fun guideEpgCoverageLine(accountId: String): String {
    val mapped = runCatching { EpgMirrorRepository.mappingFor(accountId).size }.getOrDefault(0)
    val total = runCatching { IptvContentDb.ingestMeta(accountId)?.liveCount }.getOrNull()
        ?.takeIf { it > 0 }
    val coverage = when {
        mapped <= 0 -> "Backup guide (EPG mirror): no channels matched yet."
        total != null -> "Backup guide (EPG mirror): $mapped of $total channels matched."
        else -> "Backup guide (EPG mirror): $mapped channels matched."
    }
    val tally = EpgSourceLadder.sessionMemory.tally(accountId)
    if (tally.total == 0) return coverage
    val parts = buildList {
        if (tally.manual > 0) add("manual ${tally.manual}")
        if (tally.provider > 0) add("provider ${tally.provider}")
        if (tally.mirror > 0) add("backup ${tally.mirror}")
        if (tally.none > 0) add("none ${tally.none}")
    }
    return coverage + "\nGuide sources this session — " + parts.joinToString(" · ") + "."
}

private const val CORRECTION_STEP_MINUTES = 30

private fun formatCorrection(minutes: Int): String {
    if (minutes == 0) return "None"
    val sign = if (minutes > 0) "+" else "−"
    val abs = if (minutes < 0) -minutes else minutes
    val h = abs / 60
    val m = abs % 60
    return if (m == 0) "$sign${h}h" else "$sign${h}h ${m}m"
}

/** Same scale as [formatCorrection] but 0 reads "Auto": unset means detect, not "+0". */
private fun formatGuideOffset(minutes: Int): String =
    if (minutes == 0) "Auto" else formatCorrection(minutes)

@Composable
private fun ContentTypeList(
    account: XtreamAccount,
    categories: Map<String, List<XtreamCategory>>,
    isTablet: Boolean,
    onOpenType: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = account.name, isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            TYPE_LABELS.forEachIndexed { index, (type, label) ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                val enabled = account.typeEnabled(type)
                val selection = account.categorySelections.forType(type)
                val total = categories[type]?.size
                val subtitle = when {
                    !enabled -> "Hidden"
                    selection == null -> "All categories"
                    total != null -> "${selection.size}/$total categories"
                    else -> "${selection.size} selected"
                }
                SettingsSwitchRow(
                    title = label,
                    description = subtitle,
                    checked = enabled,
                    isTablet = isTablet,
                    onRowClick = { if (enabled) onOpenType(type) },
                    onCheckedChange = { on ->
                        XtreamRepository.updateOptions(account.id) { acc ->
                            acc.copy(contentTypes = if (on) acc.contentTypes + type else acc.contentTypes - type)
                        }
                    },
                )
            }
        }
        Text(
            text = "Tap a content type to choose its categories. Toggling a type off hides it from browse and search for this playlist.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
        )
    }
}

/**
 * Category checklist for one content type of one playlist — its own settings page so the standard
 * header + system back pop correctly to the type list. Fetches its own type's categories (id + name)
 * and offers Select All / Deselect All as section actions.
 */
internal fun LazyListScope.xtreamCategoryChecklistContent(
    isTablet: Boolean,
    state: XtreamUiState,
) {
    item {
        val account = state.accounts.firstOrNull { it.id == XtreamContentPage.accountId }
        val type = XtreamContentPage.type
        if (account == null || type == null) {
            PlaylistGoneCard(isTablet = isTablet)
            return@item
        }

        val label = labelForType(type)
        var categories by remember(account.id, type) { mutableStateOf<List<XtreamCategory>?>(null) }
        var failed by remember(account.id, type) { mutableStateOf(false) }
        var fetchAttempt by remember(account.id, type) { mutableStateOf(0) }
        LaunchedEffect(account.id, type, fetchAttempt) {
            failed = false   // back to the spinner while (re)fetching
            val client = IptvClient.forAccount(account)
            val fetched = when (type) {
                CONTENT_TYPE_LIVE -> client.liveCategories(account)
                CONTENT_TYPE_MOVIES -> client.vodCategories(account)
                else -> client.seriesCategories(account)
            }.getOrNull()
            if (fetched != null) categories = fetched else failed = true
        }

        CategoryChecklist(
            account = account,
            type = type,
            label = label,
            categories = categories,
            failed = failed,
            isTablet = isTablet,
            onRetry = {
                // User-driven retry: clear the panel breaker FIRST (WP6) so it can never
                // fast-fail the very attempt the user just asked for.
                IptvPanelGuard.resetForAccount(account)
                fetchAttempt++
            },
        )
    }
}

@Composable
private fun CategoryChecklist(
    account: XtreamAccount,
    type: String,
    label: String,
    categories: List<XtreamCategory>?,
    failed: Boolean,
    isTablet: Boolean,
    onRetry: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val selection = account.categorySelections.forType(type)
    val count = when {
        categories == null -> if (failed) "Not loaded" else "Loading…"
        selection == null -> "${categories.size}/${categories.size} selected"
        else -> "${selection.size}/${categories.size} selected"
    }
    SettingsSection(
        title = "$label categories",
        isTablet = isTablet,
        actions = {
            // Select all = null selection: every category, including ones the provider adds later.
            NuvioActionLabel(
                text = "Select all",
                onClick = { setSelection(account.id, type, null) },
            )
            Spacer(modifier = Modifier.width(tokens.spacing.controlGap))
            NuvioActionLabel(
                text = "Deselect all",
                onClick = { setSelection(account.id, type, emptyList()) },
            )
        },
    ) {
        Text(
            text = count,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(bottom = NuvioTokens.Space.s10),
        )
        SettingsGroup(isTablet = isTablet) {
            when {
                categories == null && failed -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16,
                            vertical = if (isTablet) NuvioTokens.Space.s16 else NuvioTokens.Space.s14,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Couldn't load categories.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.danger,
                        modifier = Modifier.weight(1f),
                    )
                    NuvioActionLabel(text = "Retry", onClick = onRetry)
                }
                categories == null -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s24),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(strokeWidth = tokens.borders.medium, color = tokens.colors.accent) }
                categories.isEmpty() -> Text(
                    text = "No categories on this playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.padding(
                        horizontal = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16,
                        vertical = if (isTablet) NuvioTokens.Space.s16 else NuvioTokens.Space.s14,
                    ),
                )
                else -> {
                    val allIds = remember(categories) { categories.map { it.id } }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (isTablet) 900.dp else 680.dp),
                    ) {
                        itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
                            val checked = selection == null || category.id in selection
                            Column {
                                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                                CategoryChecklistRow(
                                    name = category.name.ifBlank { "Other" },
                                    checked = checked,
                                    isTablet = isTablet,
                                    onToggle = { toggleCategory(account.id, type, allIds, category.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChecklistRow(
    name: String,
    checked: Boolean,
    isTablet: Boolean,
    onToggle: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val verticalPadding = if (isTablet) NuvioTokens.Space.s10 else NuvioTokens.Space.s8
    val horizontalPadding = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = tokens.colors.accent,
                checkmarkColor = tokens.colors.onAccent,
                uncheckedColor = tokens.colors.borderDefault,
            ),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            modifier = Modifier
                .padding(start = NuvioTokens.Space.s4)
                .then(if (isTablet) Modifier.widthIn(max = 560.dp) else Modifier),
        )
    }
}

private fun setSelection(accountId: String, type: String, selection: List<String>?) {
    XtreamRepository.updateOptions(accountId) {
        it.copy(categorySelections = it.categorySelections.withType(type, selection))
    }
}

/** Toggling from "all" (null) materializes the full id list first, then flips the one id. */
private fun toggleCategory(accountId: String, type: String, allIds: List<String>, categoryId: String) {
    XtreamRepository.updateOptions(accountId) { acc ->
        val current = acc.categorySelections.forType(type) ?: allIds
        val updated = if (categoryId in current) current - categoryId else current + categoryId
        acc.copy(categorySelections = acc.categorySelections.withType(type, updated))
    }
}
