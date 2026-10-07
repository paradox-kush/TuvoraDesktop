package com.nuvio.app.features.mediaserver.internal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.flow.ApproveEligibility
import com.nuvio.app.features.mediaserver.internal.flow.MediaServerListController
import com.nuvio.app.features.mediaserver.internal.flow.ServerRowModel
import com.nuvio.app.features.mediaserver.internal.flow.ServerStatus
import com.nuvio.app.features.mediaserver.api.MediaServerPages
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ms_add_row_description
import nuvio.composeapp.generated.resources.ms_add_row_title
import nuvio.composeapp.generated.resources.ms_approve_row_description
import nuvio.composeapp.generated.resources.ms_approve_row_title
import nuvio.composeapp.generated.resources.ms_empty_body
import nuvio.composeapp.generated.resources.ms_list_section_add
import nuvio.composeapp.generated.resources.ms_list_section_servers
import nuvio.composeapp.generated.resources.ms_status_checking
import nuvio.composeapp.generated.resources.ms_status_disabled
import nuvio.composeapp.generated.resources.ms_status_needs_sign_in
import nuvio.composeapp.generated.resources.ms_status_offline
import nuvio.composeapp.generated.resources.ms_status_sign_in_again
import nuvio.composeapp.generated.resources.ms_status_signed_in
import org.jetbrains.compose.resources.stringResource

/**
 * The server list: add / approve at the top (like the IPTV page's "Add Playlist"), then the servers with a status
 * badge. State lives in [MediaServerListController]; this only renders it. A reachability check runs once each time
 * the page becomes visible - never on a timer.
 */
internal fun LazyListScope.mediaServerListContent(
    isTablet: Boolean,
    onAdd: () -> Unit,
    onApprove: () -> Unit,
    onOpen: (MediaServerEntry) -> Unit,
) {
    item {
        val runtime = remember { MediaServerRuntime.production.also { it.entryStore.ensureLoaded() } }
        val services = runtime.services
        val scope = rememberCoroutineScope()
        val controller = remember { MediaServerListController(services, scope) }
        val entries by runtime.entryStore.entries.collectAsStateWithLifecycle()
        val expired by services.expiredSessions.collectAsStateWithLifecycle()
        val credentialVersion by services.credentialVersion.collectAsStateWithLifecycle()
        val health by controller.healthState.collectAsStateWithLifecycle()
        val checking by controller.checkingState.collectAsStateWithLifecycle()
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner, entries.map { it.key }) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { controller.checkOnce(runtime.entryStore.current()) }
        }
        val rows = remember(entries, expired, health, checking, credentialVersion) { controller.rows(entries, expired, health, checking) }
        val canApprove = remember(entries, credentialVersion) { ApproveEligibility.any(entries, services) }

        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap)) {
            SettingsSection(title = stringResource(Res.string.ms_list_section_add), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.ms_add_row_title),
                        description = stringResource(Res.string.ms_add_row_description),
                        isTablet = isTablet,
                        onClick = onAdd,
                    )
                    if (canApprove) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.ms_approve_row_title),
                            description = stringResource(Res.string.ms_approve_row_description),
                            isTablet = isTablet,
                            onClick = onApprove,
                        )
                    }
                }
            }
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(Res.string.ms_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                SettingsSection(title = stringResource(Res.string.ms_list_section_servers), isTablet = isTablet) {
                    SettingsGroup(isTablet = isTablet) {
                        rows.forEachIndexed { index, row ->
                            if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                            ServerRow(row = row, isTablet = isTablet, onClick = { onOpen(row.entry) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(row: ServerRowModel, isTablet: Boolean, onClick: () -> Unit) {
    SettingsNavigationRow(
        title = row.entry.name,
        description = serverSubtitle(row.entry),
        isTablet = isTablet,
        onClick = onClick,
        trailingContent = { StatusBadge(row.status, row.checking) },
    )
}

/** "Jellyfin · kid · nas.local:8096" - product, the signed-in user's name when this device knows it, the host. */
@Composable
internal fun serverSubtitle(entry: MediaServerEntry): String =
    listOfNotNull(entry.type.productName, entry.userName?.takeIf { it.isNotBlank() }, hostOf(entry.address)).joinToString(" · ")

internal fun hostOf(address: String?): String? =
    address?.substringAfter("://")?.substringBefore('/')?.takeIf { it.isNotBlank() }

@Composable
internal fun StatusBadge(status: ServerStatus, checking: Boolean) {
    val tokens = MaterialTheme.nuvio
    val label = when {
        checking && status == ServerStatus.SIGNED_IN -> stringResource(Res.string.ms_status_checking)
        else -> when (status) {
            ServerStatus.SIGNED_IN -> stringResource(Res.string.ms_status_signed_in)
            ServerStatus.SIGN_IN_AGAIN -> stringResource(Res.string.ms_status_sign_in_again)
            ServerStatus.NEEDS_SIGN_IN -> stringResource(Res.string.ms_status_needs_sign_in)
            ServerStatus.OFFLINE -> stringResource(Res.string.ms_status_offline)
            ServerStatus.DISABLED -> stringResource(Res.string.ms_status_disabled)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            ServerStatus.SIGN_IN_AGAIN, ServerStatus.NEEDS_SIGN_IN -> Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.accent,
            )
            else -> NuvioInfoBadge(text = label)
        }
    }
}
