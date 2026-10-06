package com.nuvio.app.features.mediaserver.internal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.MediaServerAccounts
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.client.MediaServerTrust
import com.nuvio.app.features.mediaserver.internal.flow.AddError
import com.nuvio.app.features.mediaserver.internal.flow.AddServerController
import com.nuvio.app.features.mediaserver.internal.flow.AddServerState
import com.nuvio.app.features.mediaserver.internal.flow.AddStage
import com.nuvio.app.features.mediaserver.internal.policy.QuickConnectPolicy
import com.nuvio.app.features.mediaserver.api.MediaServerPages
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSecretTextField
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.ms_add_address_hint
import nuvio.composeapp.generated.resources.ms_add_address_label
import nuvio.composeapp.generated.resources.ms_add_back
import nuvio.composeapp.generated.resources.ms_add_cert_body
import nuvio.composeapp.generated.resources.ms_add_cert_fingerprint
import nuvio.composeapp.generated.resources.ms_add_cert_title
import nuvio.composeapp.generated.resources.ms_add_cert_trust
import nuvio.composeapp.generated.resources.ms_add_change_address
import nuvio.composeapp.generated.resources.ms_add_checking
import nuvio.composeapp.generated.resources.ms_add_connect
import nuvio.composeapp.generated.resources.ms_add_connected
import nuvio.composeapp.generated.resources.ms_add_error_different_server
import nuvio.composeapp.generated.resources.ms_add_error_invalid_address
import nuvio.composeapp.generated.resources.ms_add_error_not_a_server
import nuvio.composeapp.generated.resources.ms_add_error_not_saved
import nuvio.composeapp.generated.resources.ms_add_error_quick_connect
import nuvio.composeapp.generated.resources.ms_add_error_sign_in_failed
import nuvio.composeapp.generated.resources.ms_add_error_unreachable
import nuvio.composeapp.generated.resources.ms_add_error_unusable
import nuvio.composeapp.generated.resources.ms_add_error_wrong_credentials
import nuvio.composeapp.generated.resources.ms_add_intro
import nuvio.composeapp.generated.resources.ms_add_password_label
import nuvio.composeapp.generated.resources.ms_add_password_note
import nuvio.composeapp.generated.resources.ms_add_password_row
import nuvio.composeapp.generated.resources.ms_add_password_row_description
import nuvio.composeapp.generated.resources.ms_add_quick_connect_row
import nuvio.composeapp.generated.resources.ms_add_quick_connect_row_description
import nuvio.composeapp.generated.resources.ms_add_qc_expires
import nuvio.composeapp.generated.resources.ms_add_qc_steps
import nuvio.composeapp.generated.resources.ms_add_qc_title
import nuvio.composeapp.generated.resources.ms_add_qc_waiting
import nuvio.composeapp.generated.resources.ms_add_sign_in
import nuvio.composeapp.generated.resources.ms_add_signed_in_toast
import nuvio.composeapp.generated.resources.ms_add_signing_in
import nuvio.composeapp.generated.resources.ms_add_type_auto
import nuvio.composeapp.generated.resources.ms_add_type_corrected
import nuvio.composeapp.generated.resources.ms_add_username_label
import nuvio.composeapp.generated.resources.ms_add_use_password
import org.jetbrains.compose.resources.stringResource

/**
 * Add a server / sign this device in to one (design 5.3, 5.4): address -> (certificate) -> sign-in choice ->
 * Quick Connect code or username + password. The flow's state machine is [AddServerController]; this renders it.
 * The Quick Connect poll runs under `repeatOnLifecycle(RESUMED)`, so it stops in the background and with the screen.
 */
internal fun LazyListScope.mediaServerAddContent(isTablet: Boolean, onDone: () -> Unit) {
    item {
        val runtime = remember { MediaServerRuntime.production.also { it.entryStore.ensureLoaded() } }
        val existing = remember { MediaServerPages.signInKey?.let(runtime.entryStore::entryByKey) }
        val scope = rememberCoroutineScope()
        val controller = remember {
            AddServerController(
                services = runtime.services,
                accounts = MediaServerAccounts(runtime.entryStore, runtime.services),
                trust = MediaServerTrust.shared,
                scope = scope,
                existing = existing,
                onSignedIn = { entry -> MediaServerHomeSurfaces.changed(runtime, entry) },
            )
        }
        DisposableEffect(controller) { onDispose { controller.cancel() } }
        val state by controller.state.collectAsStateWithLifecycle()

        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap)) {
            when (state.stage) {
                AddStage.ADDRESS -> AddressStep(state, existingName = existing?.name, controller = controller, isTablet = isTablet)
                AddStage.CHOOSE_SIGN_IN -> ChooseStep(state, controller, isTablet)
                AddStage.QUICK_CONNECT -> QuickConnectStep(state, controller, runtime.nowMs)
                AddStage.PASSWORD -> PasswordStep(state, controller)
                AddStage.DONE -> {
                    val toast = stringResource(Res.string.ms_add_signed_in_toast, state.signedIn?.name.orEmpty())
                    LaunchedEffect(state.signedIn?.key) {
                        NuvioToastController.show(toast)
                        onDone()
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressStep(state: AddServerState, existingName: String?, controller: AddServerController, isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    Text(text = stringResource(Res.string.ms_add_intro), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
    if (existingName == null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = stringResource(Res.string.ms_add_type_auto), selected = state.selectedType == null, onClick = { controller.selectType(null) })
            MediaServerType.entries.forEach { type ->
                Chip(label = type.productName, selected = state.selectedType == type, onClick = { controller.selectType(type) })
            }
        }
    }
    Text(text = stringResource(Res.string.ms_add_address_label), style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
    NuvioInputField(
        value = state.address,
        onValueChange = controller::setAddress,
        placeholder = stringResource(Res.string.ms_add_address_hint),
    )
    state.error?.let { ErrorText(it) }
    NuvioPrimaryButton(
        text = if (state.busy) stringResource(Res.string.ms_add_checking) else stringResource(Res.string.ms_add_connect),
        enabled = !state.busy && state.address.isNotBlank(),
        onClick = controller::connect,
    )
    state.certPrompt?.let { prompt ->
        DialogSurface(
            onDismissRequest = controller::declineCertificate,
            title = stringResource(Res.string.ms_add_cert_title),
            message = stringResource(Res.string.ms_add_cert_body),
        ) {
            Text(text = stringResource(Res.string.ms_add_cert_fingerprint), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
            Text(
                text = prompt.fingerprint.chunked(2).joinToString(":").takeIf { prompt.fingerprint.length % 2 == 0 && ':' !in prompt.fingerprint } ?: prompt.fingerprint,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = tokens.colors.textPrimary,
            )
            Text(text = prompt.authority, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            DialogButtons {
                DialogButton(text = stringResource(Res.string.action_cancel), onClick = controller::declineCertificate, style = DialogButtonStyle.Secondary)
                DialogButton(text = stringResource(Res.string.ms_add_cert_trust), onClick = controller::trustCertificate, style = DialogButtonStyle.Primary)
            }
        }
    }
}

@Composable
private fun ChooseStep(state: AddServerState, controller: AddServerController, isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    val found = state.found
    if (found != null) {
        Text(
            text = stringResource(Res.string.ms_add_connected, found.info.name, listOfNotNull(found.type.productName, found.info.version).joinToString(" ")),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
        )
    }
    state.typeCorrectedFrom?.let { picked ->
        Text(
            text = stringResource(Res.string.ms_add_type_corrected, picked.productName, state.found?.type?.productName.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
    SettingsGroup(isTablet = isTablet) {
        if (state.quickConnectAvailable) {
            SettingsNavigationRow(
                title = stringResource(Res.string.ms_add_quick_connect_row),
                description = stringResource(Res.string.ms_add_quick_connect_row_description),
                isTablet = isTablet,
                onClick = controller::startQuickConnect,
            )
            SettingsGroupDivider(isTablet = isTablet)
        }
        SettingsNavigationRow(
            title = stringResource(Res.string.ms_add_password_row),
            description = stringResource(Res.string.ms_add_password_row_description),
            isTablet = isTablet,
            onClick = controller::usePassword,
        )
    }
    state.error?.let { ErrorText(it) }
    NuvioActionLabel(text = stringResource(Res.string.ms_add_change_address), onClick = controller::backToAddress)
}

@Composable
private fun QuickConnectStep(state: AddServerState, controller: AddServerController, nowMs: () -> Long) {
    val tokens = MaterialTheme.nuvio
    val lifecycleOwner = LocalLifecycleOwner.current
    // The poll belongs to the screen's RESUMED lifetime: backgrounded or left = cancelled; back = a fresh code.
    LaunchedEffect(lifecycleOwner, controller) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { controller.runQuickConnect() }
    }
    Text(text = stringResource(Res.string.ms_add_qc_title), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
    Text(
        text = stringResource(Res.string.ms_add_qc_steps, state.found?.info?.name.orEmpty()),
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textMuted,
    )
    NuvioSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val qc = state.quickConnect
            if (qc == null) {
                NuvioLoadingIndicator(modifier = Modifier.size(28.dp))
            } else {
                Text(
                    text = qc.displayCode,
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
                    color = tokens.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                var clock by remember(qc.startedAtMs) { mutableLongStateOf(nowMs()) }
                // A local display tick for the countdown only (no network): it lives and dies with this visible screen.
                LaunchedEffect(qc.startedAtMs) {
                    while (true) {
                        clock = nowMs()
                        delay(1_000)
                    }
                }
                Text(
                    text = stringResource(Res.string.ms_add_qc_expires, QuickConnectPolicy.countdownLabel(QuickConnectPolicy.remainingMs(qc.startedAtMs, clock))),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                Text(text = stringResource(Res.string.ms_add_qc_waiting), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
        }
    }
    NuvioActionLabel(text = stringResource(Res.string.ms_add_use_password), onClick = controller::usePassword)
    NuvioActionLabel(text = stringResource(Res.string.ms_add_back), onClick = controller::backToChoice)
}

@Composable
private fun PasswordStep(state: AddServerState, controller: AddServerController) {
    val tokens = MaterialTheme.nuvio
    var username by rememberSaveable { mutableStateOf("") }
    // Deliberately NOT rememberSaveable: a typed password must never be written into saved UI state.
    var password by remember { mutableStateOf("") }
    if (state.publicUsers.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.publicUsers.forEach { user ->
                Chip(label = user.name, selected = username == user.name, onClick = { username = user.name })
            }
        }
    }
    NuvioInputField(value = username, onValueChange = { username = it }, placeholder = stringResource(Res.string.ms_add_username_label))
    SettingsSecretTextField(
        value = password,
        onValueChange = { password = it },
        label = stringResource(Res.string.ms_add_password_label),
        modifier = Modifier.fillMaxWidth(),
        isError = state.error == AddError.WRONG_CREDENTIALS,
    )
    Text(text = stringResource(Res.string.ms_add_password_note), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
    state.error?.let { ErrorText(it) }
    NuvioPrimaryButton(
        text = if (state.busy) stringResource(Res.string.ms_add_signing_in) else stringResource(Res.string.ms_add_sign_in),
        enabled = !state.busy && username.isNotBlank(),
        onClick = {
            controller.submitPassword(username, password)
            password = ""
        },
    )
    NuvioActionLabel(text = stringResource(Res.string.ms_add_back), onClick = controller::backToChoice)
}

@Composable
private fun ErrorText(error: AddError) {
    val text = when (error) {
        AddError.INVALID_ADDRESS -> stringResource(Res.string.ms_add_error_invalid_address)
        AddError.NOT_A_MEDIA_SERVER -> stringResource(Res.string.ms_add_error_not_a_server)
        AddError.UNREACHABLE -> stringResource(Res.string.ms_add_error_unreachable)
        AddError.WRONG_CREDENTIALS -> stringResource(Res.string.ms_add_error_wrong_credentials)
        AddError.QUICK_CONNECT_FAILED -> stringResource(Res.string.ms_add_error_quick_connect)
        AddError.SIGN_IN_FAILED -> stringResource(Res.string.ms_add_error_sign_in_failed)
        AddError.DIFFERENT_SERVER -> stringResource(Res.string.ms_add_error_different_server)
        AddError.NOT_SAVED -> stringResource(Res.string.ms_add_error_not_saved)
        AddError.UNUSABLE_SERVER -> stringResource(Res.string.ms_add_error_unusable)
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.danger)
}
