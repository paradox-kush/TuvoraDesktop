package com.nuvio.app.features.radar.alerts

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.contracts.SportsAlertsSettingsSection
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow

/**
 * "Sports alerts" in Settings > Notifications (phone, iPad, Desktop). Switch rows and cycle rows (tap = next
 * value) over the per-profile, synced [SportsAlertSettings]. Thin: reads [SportsAlerts.settingsView], calls its actions.
 */
internal object SportsAlertsSettingsSectionImpl : SportsAlertsSettingsSection {
    override fun LazyListScope.sportsAlertsSettings(isTablet: Boolean) {
        item(key = "sports_alerts") {
            SportsAlertsSettingsContent(isTablet)
        }
    }
}

@Composable
private fun SportsAlertsSettingsContent(isTablet: Boolean) {
    val flow = remember { SportsAlerts.settingsView }
    val view by flow.collectAsStateWithLifecycle(SportsAlertSettingsView())
    val s = view.settings
    SettingsSection(title = "Sports alerts", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = "Sports alerts",
                description = if (view.signedIn) {
                    "Kick-offs, goals and full time for the teams and leagues you follow, while the app is open."
                } else {
                    SportsAlertLabels.SIGN_IN
                },
                checked = view.signedIn && s.enabled,
                enabled = view.signedIn,
                isTablet = isTablet,
                onCheckedChange = SportsAlerts::setEnabled,
            )
            if (view.signedIn && s.enabled) {
                SettingsGroupDivider(isTablet = isTablet)
                CycleRow("Teams you follow", "What to show for a followed club's games", SportsAlertLabels.level(s.teamLevel), isTablet, SportsAlerts::cycleTeamLevel)
                SettingsGroupDivider(isTablet = isTablet)
                CycleRow("Leagues you follow", "What to show for other games in a followed league", SportsAlertLabels.level(s.leagueLevel), isTablet, SportsAlerts::cycleLeagueLevel)
                SettingsGroupDivider(isTablet = isTablet)
                if (SportsAlerts.duringPlaybackConfigurable) {
                    CycleRow("While something plays", "Banners over the player", SportsAlertLabels.duringPlayback(s.duringPlayback), isTablet, SportsAlerts::cycleDuringPlayback)
                } else {
                    // Desktop: the native video layer covers the app, so a banner could not show over a player. Fixed, not a choice.
                    CycleRow("While something plays", "The video covers the app on Desktop, so banners wait until it stops", SportsAlertLabels.duringPlayback(DuringPlayback.Never), isTablet, onClick = null)
                }
                SettingsGroupDivider(isTablet = isTablet)
                CycleRow("At most", "Banners in any hour", SportsAlertLabels.perHour(s.maxPerHour), isTablet, SportsAlerts::cyclePerHour)
                SettingsGroupDivider(isTablet = isTablet)
                CycleRow("Group updates", "Updates that land close together arrive as one banner", SportsAlertLabels.batch(s.batchSeconds), isTablet, SportsAlerts::cycleBatch)
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Quiet hours",
                    description = "No sports banners in this window. Alerts are dropped, not saved for later.",
                    checked = s.quietOn,
                    isTablet = isTablet,
                    onCheckedChange = SportsAlerts::setQuietOn,
                )
                if (s.quietOn) {
                    SettingsGroupDivider(isTablet = isTablet)
                    TimeStepperRow(label = "From", minute = s.quietStartMin, isTablet = isTablet, onChange = { SportsAlerts.setQuietRange(it, s.quietEndMin) })
                    SettingsGroupDivider(isTablet = isTablet)
                    TimeStepperRow(label = "Until", minute = s.quietEndMin, isTablet = isTablet, onChange = { SportsAlerts.setQuietRange(s.quietStartMin, it) })
                }
            }
        }
        if (view.signedIn && s.enabled && (view.teams.isNotEmpty() || view.leagues.isNotEmpty())) {
            SettingsGroup(isTablet = isTablet) {
                view.teams.forEachIndexed { i, (id, name) ->
                    if (i > 0) SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = name,
                        description = "Team",
                        checked = id !in s.mutedTeams,
                        isTablet = isTablet,
                        onCheckedChange = { on -> SportsAlerts.setTeamMuted(id, !on) },
                    )
                }
                view.leagues.forEachIndexed { i, (id, name) ->
                    if (i > 0 || view.teams.isNotEmpty()) SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = name,
                        description = "League",
                        checked = id !in s.mutedLeagues,
                        isTablet = isTablet,
                        onCheckedChange = { on -> SportsAlerts.setLeagueMuted(id, !on) },
                    )
                }
            }
        }
    }
}

/** A setting with a few fixed values: the current one on the right, one tap moves to the next. */
@Composable
private fun CycleRow(title: String, description: String, value: String, isTablet: Boolean, onClick: (() -> Unit)?) {
    SettingsNavigationRow(
        title = title,
        description = description,
        isTablet = isTablet,
        enabled = onClick != null,
        trailingContent = {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textMuted)
        },
        onClick = onClick ?: {},
    )
}

/** A time of day with minus and plus buttons in half-hour steps: no dialog, nothing to dismiss. */
@Composable
private fun TimeStepperRow(label: String, minute: Int, isTablet: Boolean, onChange: (Int) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        IconButton(onClick = { onChange(SportsAlertLabels.stepClock(minute, -1)) }) {
            Icon(Icons.Default.Remove, contentDescription = "Earlier", tint = tokens.colors.textPrimary)
        }
        Text(
            text = SportsAlertLabels.clock(minute),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = { onChange(SportsAlertLabels.stepClock(minute, 1)) }) {
            Icon(Icons.Default.Add, contentDescription = "Later", tint = tokens.colors.textPrimary)
        }
    }
}
