package com.nuvio.app.features.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsSwitchRow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_pin_suggest_clear_action
import nuvio.composeapp.generated.resources.iptv_pin_suggest_clear_cancel
import nuvio.composeapp.generated.resources.iptv_pin_suggest_clear_confirm
import nuvio.composeapp.generated.resources.iptv_pin_suggest_clear_desc
import nuvio.composeapp.generated.resources.iptv_pin_suggest_clear_done
import nuvio.composeapp.generated.resources.iptv_pin_suggest_clear_title
import nuvio.composeapp.generated.resources.iptv_pin_suggest_privacy
import nuvio.composeapp.generated.resources.iptv_pin_suggest_settings_group
import nuvio.composeapp.generated.resources.iptv_pin_suggest_switch_desc
import nuvio.composeapp.generated.resources.iptv_pin_suggest_switch_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The "Suggestions" group of the IPTV settings: the switch that turns pin suggestions off (the user's
 * control over the feature), and "Clear play counts", which confirms on its own row — no popup. The play
 * counts live on this device only, and the footer says so.
 */
@Composable
internal fun LivePinSuggestionsSettings(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val store = LivePinSuggestions.store
    LaunchedEffect(Unit) { store.ensureLoaded() }
    val state by store.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.iptv_pin_suggest_settings_group),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.Bold,
        )
        SettingsSwitchRow(
            title = stringResource(Res.string.iptv_pin_suggest_switch_title),
            description = stringResource(Res.string.iptv_pin_suggest_switch_desc),
            checked = state.enabled,
            isTablet = true,
            horizontalPadding = 12.dp,
            onCheckedChange = store::setEnabled,
        )
        if (!confirming) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(Res.string.iptv_pin_suggest_clear_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(Res.string.iptv_pin_suggest_clear_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
                TextButton(onClick = { confirming = true }, enabled = state.channels.isNotEmpty()) {
                    Text(stringResource(Res.string.iptv_pin_suggest_clear_action), color = tokens.colors.accent)
                }
            }
        } else {
            // The confirmation takes the row's place: a stray click cannot clear, and nothing floats over the list.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.iptv_pin_suggest_clear_confirm),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(Res.string.iptv_pin_suggest_clear_cancel), color = tokens.colors.textSecondary)
                }
                TextButton(onClick = {
                    store.clearPlayCounts()
                    confirming = false
                    scope.launch { NuvioToastController.show(getString(Res.string.iptv_pin_suggest_clear_done)) }
                }) {
                    Text(stringResource(Res.string.iptv_pin_suggest_clear_action), color = tokens.colors.danger)
                }
            }
        }
        Text(
            stringResource(Res.string.iptv_pin_suggest_privacy),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
}
