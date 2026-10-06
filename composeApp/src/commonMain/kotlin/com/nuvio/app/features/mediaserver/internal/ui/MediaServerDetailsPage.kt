package com.nuvio.app.features.mediaserver.internal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.api.MediaServerHomeRow
import com.nuvio.app.features.mediaserver.internal.MediaServerAccounts
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.flow.MediaServerManagement
import com.nuvio.app.features.mediaserver.internal.flow.MediaServerStatusPolicy
import com.nuvio.app.features.mediaserver.internal.flow.ServerStatus
import com.nuvio.app.features.mediaserver.internal.source.MediaServerLibraries
import com.nuvio.app.features.mediaserver.api.MediaServerPages
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.ms_details_address
import nuvio.composeapp.generated.resources.ms_details_enabled
import nuvio.composeapp.generated.resources.ms_details_enabled_hint
import nuvio.composeapp.generated.resources.ms_details_gone
import nuvio.composeapp.generated.resources.ms_details_home_continue
import nuvio.composeapp.generated.resources.ms_details_home_hint
import nuvio.composeapp.generated.resources.ms_details_home_next_up
import nuvio.composeapp.generated.resources.ms_details_home_recent
import nuvio.composeapp.generated.resources.ms_details_libraries_empty
import nuvio.composeapp.generated.resources.ms_details_libraries_failed
import nuvio.composeapp.generated.resources.ms_details_libraries_hint
import nuvio.composeapp.generated.resources.ms_details_name
import nuvio.composeapp.generated.resources.ms_details_remove
import nuvio.composeapp.generated.resources.ms_details_remove_body
import nuvio.composeapp.generated.resources.ms_details_remove_confirm
import nuvio.composeapp.generated.resources.ms_details_remove_purge
import nuvio.composeapp.generated.resources.ms_details_remove_title
import nuvio.composeapp.generated.resources.ms_details_rename_title
import nuvio.composeapp.generated.resources.ms_details_section_home
import nuvio.composeapp.generated.resources.ms_details_section_libraries
import nuvio.composeapp.generated.resources.ms_details_section_manage
import nuvio.composeapp.generated.resources.ms_details_sign_in
import nuvio.composeapp.generated.resources.ms_details_sign_in_hint_again
import nuvio.composeapp.generated.resources.ms_details_sign_in_hint_new
import nuvio.composeapp.generated.resources.ms_details_sign_out
import nuvio.composeapp.generated.resources.ms_details_sign_out_body
import nuvio.composeapp.generated.resources.ms_details_sign_out_confirm
import nuvio.composeapp.generated.resources.ms_details_sign_out_title
import nuvio.composeapp.generated.resources.ms_details_sync_address
import nuvio.composeapp.generated.resources.ms_details_sync_address_hint
import nuvio.composeapp.generated.resources.ms_details_user
import org.jetbrains.compose.resources.stringResource

/**
 * One server: status header, Home rows (opt-in, off by default), libraries (each can become a Home row), and
 * Manage (rename, on/off, address sync with the plain-language token explanation, sign out, remove). A page, not a
 * dialog (the owner's call for the playlist details applies here too); only the destructive confirmations and the
 * rename field are dialogs.
 */
internal fun LazyListScope.mediaServerDetailsContent(
    isTablet: Boolean,
    onSignIn: (MediaServerEntry) -> Unit,
    onLeave: () -> Unit,
) {
    item {
        val runtime = remember { MediaServerRuntime.production.also { it.entryStore.ensureLoaded() } }
        val services = runtime.services
        val entries by runtime.entryStore.entries.collectAsStateWithLifecycle()
        val expired by services.expiredSessions.collectAsStateWithLifecycle()
        val credentialVersion by services.credentialVersion.collectAsStateWithLifecycle()
        val key = MediaServerPages.detailsKey
        val entry = entries.firstOrNull { it.key == key }
        if (entry == null) {
            Text(
                text = stringResource(Res.string.ms_details_gone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textMuted,
            )
            return@item
        }
        val scope = rememberCoroutineScope()
        val management = remember {
            MediaServerManagement(
                accounts = MediaServerAccounts(runtime.entryStore, services),
                services = services,
                onChanged = { changed -> MediaServerHomeSurfaces.changed(runtime, changed) },
            )
        }
        val signedIn = remember(entry.serverKey, credentialVersion) { services.isSignedIn(entry) }
        val status = MediaServerStatusPolicy.of(entry, signedIn, entry.serverKey in expired, null)
        var renaming by remember { mutableStateOf(false) }
        var confirmSignOut by remember { mutableStateOf(false) }
        var confirmRemove by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }

        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap)) {
            HeaderCard(entry, status)

            if (status == ServerStatus.SIGN_IN_AGAIN || status == ServerStatus.NEEDS_SIGN_IN) {
                Text(
                    text = stringResource(if (status == ServerStatus.SIGN_IN_AGAIN) Res.string.ms_details_sign_in_hint_again else Res.string.ms_details_sign_in_hint_new),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
                NuvioPrimaryButton(text = stringResource(Res.string.ms_details_sign_in), onClick = { onSignIn(entry) })
            }

            SettingsSection(title = stringResource(Res.string.ms_details_section_home), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    MediaServerHomeRow.entries.forEachIndexed { index, row ->
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(rowLabel(row)),
                            checked = row in entry.homeRows,
                            isTablet = isTablet,
                            onCheckedChange = { on -> management.setHomeRow(entry, row, on) },
                        )
                    }
                }
            }
            Text(
                text = stringResource(Res.string.ms_details_home_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            if (signedIn && entry.enabled && !entry.address.isNullOrBlank()) {
                LibrariesSection(entry, runtime, isTablet) { viewId, name, on ->
                    management.setHomeLibrary(entry, viewId, name, on)
                }
            }

            SettingsSection(title = stringResource(Res.string.ms_details_section_manage), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.ms_details_name),
                        description = entry.name,
                        isTablet = isTablet,
                        onClick = { renaming = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.ms_details_enabled),
                        description = stringResource(Res.string.ms_details_enabled_hint),
                        checked = entry.enabled,
                        isTablet = isTablet,
                        onCheckedChange = { management.setEnabled(entry, it) },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.ms_details_sync_address),
                        description = stringResource(Res.string.ms_details_sync_address_hint),
                        checked = entry.syncAddress,
                        isTablet = isTablet,
                        onCheckedChange = { management.setSyncAddress(entry, it) },
                    )
                    if (signedIn) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.ms_details_sign_out),
                            description = null,
                            isTablet = isTablet,
                            onClick = { confirmSignOut = true },
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.ms_details_remove),
                        description = null,
                        isTablet = isTablet,
                        onClick = { confirmRemove = true },
                    )
                }
            }
            Spacer(Modifier.height(96.dp))
        }

        if (renaming) {
            var draft by remember(entry.key) { mutableStateOf(entry.name) }
            DialogSurface(onDismissRequest = { renaming = false }, title = stringResource(Res.string.ms_details_rename_title)) {
                NuvioInputField(value = draft, onValueChange = { draft = it }, placeholder = entry.name)
                DialogButtons {
                    DialogButton(text = stringResource(Res.string.action_cancel), onClick = { renaming = false }, style = DialogButtonStyle.Secondary)
                    DialogButton(
                        text = stringResource(Res.string.action_save),
                        onClick = { management.rename(entry, draft); renaming = false },
                        style = DialogButtonStyle.Primary,
                        enabled = draft.isNotBlank(),
                    )
                }
            }
        }
        if (confirmSignOut) {
            DialogSurface(
                onDismissRequest = { if (!busy) confirmSignOut = false },
                title = stringResource(Res.string.ms_details_sign_out_title),
                message = stringResource(Res.string.ms_details_sign_out_body, entry.name),
            ) {
                DialogButtons {
                    DialogButton(text = stringResource(Res.string.action_cancel), onClick = { confirmSignOut = false }, style = DialogButtonStyle.Secondary, enabled = !busy)
                    DialogButton(
                        text = stringResource(Res.string.ms_details_sign_out_confirm),
                        style = DialogButtonStyle.Destructive,
                        loading = busy,
                        onClick = {
                            busy = true
                            scope.launch { management.signOut(entry); busy = false; confirmSignOut = false }
                        },
                    )
                }
            }
        }
        if (confirmRemove) {
            var purge by remember { mutableStateOf(false) }
            DialogSurface(
                onDismissRequest = { if (!busy) confirmRemove = false },
                title = stringResource(Res.string.ms_details_remove_title),
                message = stringResource(Res.string.ms_details_remove_body, entry.name),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.ms_details_remove_purge),
                    checked = purge,
                    isTablet = false,
                    onCheckedChange = { purge = it },
                )
                DialogButtons {
                    DialogButton(text = stringResource(Res.string.action_cancel), onClick = { confirmRemove = false }, style = DialogButtonStyle.Secondary, enabled = !busy)
                    DialogButton(
                        text = stringResource(Res.string.ms_details_remove_confirm),
                        style = DialogButtonStyle.Destructive,
                        loading = busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val removed = management.remove(entry, purge)
                                busy = false
                                confirmRemove = false
                                if (removed) onLeave()
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun rowLabel(row: MediaServerHomeRow) = when (row) {
    MediaServerHomeRow.CONTINUE_WATCHING -> Res.string.ms_details_home_continue
    MediaServerHomeRow.NEXT_UP -> Res.string.ms_details_home_next_up
    MediaServerHomeRow.RECENTLY_ADDED -> Res.string.ms_details_home_recent
}

@Composable
private fun HeaderCard(entry: MediaServerEntry, status: ServerStatus) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = entry.name, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                StatusBadge(status, checking = false)
            }
            Text(text = serverSubtitle(entry), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        }
    }
}

/** The server's libraries, fetched once when the page opens (an authenticated call, so a revoked token is noticed here too). */
@Composable
private fun LibrariesSection(
    entry: MediaServerEntry,
    runtime: MediaServerRuntime,
    isTablet: Boolean,
    onToggleHome: (viewId: String, name: String, on: Boolean) -> Unit,
) {
    var libraries by remember(entry.serverKey) { mutableStateOf<List<MediaServerLibraries.Library>?>(null) }
    var failed by remember(entry.serverKey) { mutableStateOf(false) }
    LaunchedEffect(entry.serverKey, entry.address) {
        failed = false
        val client = runtime.services.clientFor(entry)
        if (client == null) {
            libraries = emptyList()
            return@LaunchedEffect
        }
        try {
            libraries = MediaServerLibraries.browsable(client.views())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.nuvio.app.features.mediaserver.internal.client.MediaServerException) {
            if ((e as? com.nuvio.app.features.mediaserver.internal.client.MediaServerException.Http)?.isUnauthorized == true) {
                runtime.services.onUnauthorized(entry.serverKey)
            }
            failed = true
            libraries = emptyList()
        }
    }
    SettingsSection(title = stringResource(Res.string.ms_details_section_libraries), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            val list = libraries
            when {
                list == null -> Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { NuvioLoadingIndicator(modifier = Modifier.size(24.dp)) }
                list.isEmpty() -> Text(
                    text = stringResource(if (failed) Res.string.ms_details_libraries_failed else Res.string.ms_details_libraries_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                    modifier = Modifier.padding(16.dp),
                )
                else -> list.forEachIndexed { index, library ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = library.name,
                        description = stringResource(Res.string.ms_details_libraries_hint),
                        checked = library.id in entry.homeLibraries,
                        isTablet = isTablet,
                        onCheckedChange = { on -> onToggleHome(library.id, library.name, on) },
                    )
                }
            }
        }
    }
}
