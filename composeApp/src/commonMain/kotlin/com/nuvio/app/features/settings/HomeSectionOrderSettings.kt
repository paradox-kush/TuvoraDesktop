package com.nuvio.app.features.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeSectionEntry
import com.nuvio.app.features.home.HomeSectionIds
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_reset
import nuvio.composeapp.generated.resources.home_section_catalogs_desc
import nuvio.composeapp.generated.resources.home_section_catalogs_title
import nuvio.composeapp.generated.resources.home_section_continue_watching_desc
import nuvio.composeapp.generated.resources.home_section_continue_watching_title
import nuvio.composeapp.generated.resources.home_section_hero_desc
import nuvio.composeapp.generated.resources.home_section_hero_title
import nuvio.composeapp.generated.resources.home_section_live_recents_desc
import nuvio.composeapp.generated.resources.home_section_live_recents_title
import nuvio.composeapp.generated.resources.home_section_new_episodes_desc
import nuvio.composeapp.generated.resources.home_section_new_episodes_title
import nuvio.composeapp.generated.resources.home_section_sports_desc
import nuvio.composeapp.generated.resources.home_section_sports_title
import nuvio.composeapp.generated.resources.home_section_upcoming_desc
import nuvio.composeapp.generated.resources.home_section_upcoming_title
import nuvio.composeapp.generated.resources.settings_home_order_hint
import nuvio.composeapp.generated.resources.settings_home_order_title
import nuvio.composeapp.generated.resources.settings_homescreen_reorder
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * One list for every Home section: drag to reorder, switch to hide, Reset to Default. The catalog and
 * collection rows keep their own list below; "Catalogs and collections" here is where they sit.
 */
@Composable
internal fun HomeSectionOrderSettings(
    isTablet: Boolean,
    entries: List<HomeSectionEntry>,
) {
    SettingsSection(
        title = stringResource(Res.string.settings_home_order_title),
        isTablet = isTablet,
        actions = {
            NuvioActionLabel(
                text = stringResource(Res.string.action_reset),
                onClick = HomeCatalogSettingsRepository::resetToDefaults,
            )
        },
    ) {
        val tokens = MaterialTheme.nuvio
        val hapticFeedback = LocalHapticFeedback.current
        val lazyListState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState = lazyListState) { from, to ->
            val fromId = entries.getOrNull(from.index)?.id ?: return@rememberReorderableLazyListState
            val toId = entries.getOrNull(to.index)?.id ?: return@rememberReorderableLazyListState
            HomeCatalogSettingsRepository.moveSection(fromId, toId)
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        Text(
            text = stringResource(Res.string.settings_home_order_hint),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SettingsGroup(isTablet = isTablet) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
                state = lazyListState,
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                    ReorderableItem(reorderableState, key = entry.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                        Surface(shadowElevation = elevation) {
                            Column {
                                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = homeSectionTitle(entry.id),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = tokens.colors.textPrimary,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            text = homeSectionDescription(entry.id),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = tokens.colors.textMuted,
                                        )
                                    }
                                    Switch(
                                        checked = entry.enabled,
                                        onCheckedChange = { HomeCatalogSettingsRepository.setSectionEnabled(entry.id, it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = tokens.colors.onAccent,
                                            checkedTrackColor = tokens.colors.accent,
                                            uncheckedThumbColor = tokens.colors.textMuted,
                                            uncheckedTrackColor = tokens.colors.borderDefault,
                                        ),
                                    )
                                    IconButton(
                                        modifier = with(this@ReorderableItem) {
                                            Modifier.draggableHandle(
                                                onDragStarted = {
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                onDragStopped = {
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                },
                                            )
                                        },
                                        onClick = {},
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Menu,
                                            contentDescription = stringResource(Res.string.settings_homescreen_reorder),
                                            tint = tokens.colors.textMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun homeSectionTitle(id: String): String = stringResource(
    when (id) {
        HomeSectionIds.HERO -> Res.string.home_section_hero_title
        HomeSectionIds.CONTINUE_WATCHING -> Res.string.home_section_continue_watching_title
        HomeSectionIds.LIVE_RECENTS -> Res.string.home_section_live_recents_title
        HomeSectionIds.NEW_EPISODES -> Res.string.home_section_new_episodes_title
        HomeSectionIds.SPORTS -> Res.string.home_section_sports_title
        HomeSectionIds.UPCOMING -> Res.string.home_section_upcoming_title
        else -> Res.string.home_section_catalogs_title
    },
)

@Composable
private fun homeSectionDescription(id: String): String = stringResource(
    when (id) {
        HomeSectionIds.HERO -> Res.string.home_section_hero_desc
        HomeSectionIds.CONTINUE_WATCHING -> Res.string.home_section_continue_watching_desc
        HomeSectionIds.LIVE_RECENTS -> Res.string.home_section_live_recents_desc
        HomeSectionIds.NEW_EPISODES -> Res.string.home_section_new_episodes_desc
        HomeSectionIds.SPORTS -> Res.string.home_section_sports_desc
        HomeSectionIds.UPCOMING -> Res.string.home_section_upcoming_desc
        else -> Res.string.home_section_catalogs_desc
    },
)
