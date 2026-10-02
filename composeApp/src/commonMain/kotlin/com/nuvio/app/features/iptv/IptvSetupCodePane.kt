package com.nuvio.app.features.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.provider_desktop_add_title
import nuvio.composeapp.generated.resources.provider_desktop_checking
import nuvio.composeapp.generated.resources.provider_desktop_manual_heading
import nuvio.composeapp.generated.resources.provider_desktop_route_m3u_file
import nuvio.composeapp.generated.resources.provider_desktop_route_m3u_file_desc
import nuvio.composeapp.generated.resources.provider_desktop_route_m3u_link
import nuvio.composeapp.generated.resources.provider_desktop_route_m3u_link_desc
import nuvio.composeapp.generated.resources.provider_desktop_route_stalker
import nuvio.composeapp.generated.resources.provider_desktop_route_stalker_desc
import nuvio.composeapp.generated.resources.provider_desktop_route_xtream
import nuvio.composeapp.generated.resources.provider_desktop_route_xtream_desc
import nuvio.composeapp.generated.resources.provider_setup_add_to
import nuvio.composeapp.generated.resources.provider_setup_adding
import nuvio.composeapp.generated.resources.provider_setup_cancel
import nuvio.composeapp.generated.resources.provider_setup_code_help
import nuvio.composeapp.generated.resources.provider_setup_code_label
import nuvio.composeapp.generated.resources.provider_setup_code_placeholder
import nuvio.composeapp.generated.resources.provider_setup_continue
import nuvio.composeapp.generated.resources.provider_setup_enter_other_code
import nuvio.composeapp.generated.resources.provider_setup_guest_confirm
import nuvio.composeapp.generated.resources.provider_setup_guest_message
import nuvio.composeapp.generated.resources.provider_setup_guest_title
import nuvio.composeapp.generated.resources.provider_setup_paste
import nuvio.composeapp.generated.resources.provider_setup_preview_account
import nuvio.composeapp.generated.resources.provider_setup_preview_addons
import nuvio.composeapp.generated.resources.provider_setup_preview_label
import nuvio.composeapp.generated.resources.provider_setup_preview_loading
import nuvio.composeapp.generated.resources.provider_setup_preview_playlists
import nuvio.composeapp.generated.resources.provider_setup_preview_profile
import nuvio.composeapp.generated.resources.provider_setup_section_title
import nuvio.composeapp.generated.resources.provider_setup_sign_in_needed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * "+ Add a playlist": leads with the setup-code field (Paste button, live TUV-XXXX-XXXX-XXXX formatting,
 * Continue enabled at 12 valid characters), and below it the four manual routes. Pasting a valid code swaps
 * this pane for the preview ([IptvSetupPreviewPane]). The decisions live in [SetupCodeController] and
 * [SetupCodeEntryPolicy]; this only renders them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IptvSetupCodePane(
    ui: SetupCodeUiState,
    controller: SetupCodeController,
    onPreview: () -> Unit,
    onManualRoute: (XtreamSourceType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val clipboard = LocalClipboardManager.current
    val signInNeeded = stringResource(Res.string.provider_setup_sign_in_needed)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val submit = {
        when (controller.onContinue()) {
            ContinueResult.OPEN_PREVIEW -> onPreview()
            ContinueResult.NEEDS_SIGN_IN -> if (!controller.state.value.guestPrompt) NuvioToastController.show(signInNeeded)
            ContinueResult.REJECTED -> Unit
        }
    }
    // The field owns its text (a lagging copy written back would reorder characters); the controller is told after every edit.
    var field by remember { mutableStateOf(TextFieldValue(ui.typed, TextRange(ui.typed.length))) }
    // The code is cleared (redeemed, cancelled, expired) while this pane stays: the field must not keep showing it.
    LaunchedEffect(ui.typed) {
        if (ui.typed.isEmpty() && field.text.isNotEmpty()) field = TextFieldValue("")
    }
    val place = { text: String, pasted: Boolean ->
        val grouped = SetupCode.liveFormat(text)
        field = TextFieldValue(grouped, TextRange(grouped.length))
        controller.onTyped(grouped)
        if (pasted && SetupCodeEntryPolicy.submitsOnPaste(grouped)) submit()
    }
    GuestSignInDialog(ui, controller)

    val live = SetupCodeEntryPolicy.liveProblem(ui.typed)
    val problem = ui.typedProblem ?: live

    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NuvioTokens.Radius.xl)).background(tokens.colors.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.provider_desktop_add_title), style = MaterialTheme.typography.headlineMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.provider_setup_section_title), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.provider_setup_code_help), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { next -> place(next.text, SetupCodeEntryPolicy.isPasteLike(field.text, next.text)) },
                    modifier = Modifier
                        .widthIn(max = 380.dp)
                        .weight(1f, fill = false)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                                if (SetupCodeEntryPolicy.canContinue(ui.typed)) submit()
                                true
                            } else false
                        },
                    singleLine = true,
                    isError = problem != null,
                    label = { Text(stringResource(Res.string.provider_setup_code_label)) },
                    placeholder = { Text(stringResource(Res.string.provider_setup_code_placeholder), color = tokens.colors.textDisabled) },
                    shape = RoundedCornerShape(NuvioTokens.Radius.lg),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.colors.borderFocus,
                        unfocusedBorderColor = tokens.colors.borderDefault,
                        focusedContainerColor = tokens.colors.surfaceCard,
                        unfocusedContainerColor = tokens.colors.surfaceCard,
                        cursorColor = tokens.colors.accent,
                    ),
                )
                OutlinedButton(
                    onClick = { clipboard.getText()?.text?.let { place(SetupCode.extractFromLink(it) ?: it, true) } },
                    modifier = Modifier.padding(top = 8.dp),
                    shape = tokens.shapes.button,
                    border = BorderStroke(NuvioTokens.Border.thin, tokens.colors.borderDefault),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.colors.textPrimary),
                ) {
                    Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp))
                    Text("  " + stringResource(Res.string.provider_setup_paste))
                }
            }
            problem?.let { Text(it.text(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = submit,
                    enabled = SetupCodeEntryPolicy.canContinue(ui.typed),
                    shape = tokens.shapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.colors.accent, contentColor = tokens.colors.onAccent,
                        disabledContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.disabled),
                        disabledContentColor = tokens.colors.onAccent.copy(alpha = tokens.opacity.disabled),
                    ),
                ) { Text(stringResource(Res.string.provider_setup_continue)) }
            }
        }

        Text(stringResource(Res.string.provider_desktop_manual_heading), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManualRoute(XtreamSourceType.XTREAM, Res.string.provider_desktop_route_xtream, Res.string.provider_desktop_route_xtream_desc, onManualRoute)
            ManualRoute(XtreamSourceType.STALKER, Res.string.provider_desktop_route_stalker, Res.string.provider_desktop_route_stalker_desc, onManualRoute)
            ManualRoute(XtreamSourceType.URL, Res.string.provider_desktop_route_m3u_link, Res.string.provider_desktop_route_m3u_link_desc, onManualRoute)
            ManualRoute(XtreamSourceType.FILE, Res.string.provider_desktop_route_m3u_file, Res.string.provider_desktop_route_m3u_file_desc, onManualRoute)
        }
    }
}

@Composable
private fun ManualRoute(type: XtreamSourceType, title: StringResource, description: StringResource, onClick: (XtreamSourceType) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 260.dp)
            .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
            .background(tokens.colors.surface)
            .clickable { onClick(type) }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
    }
}

/** Asks a guest before signing them out of guest mode to reach sign-in (that wipes what a guest saved on the device). */
@Composable
private fun GuestSignInDialog(ui: SetupCodeUiState, controller: SetupCodeController) {
    if (!ui.guestPrompt) return
    NuvioStatusModal(
        title = stringResource(Res.string.provider_setup_guest_title),
        message = stringResource(Res.string.provider_setup_guest_message),
        isVisible = true,
        confirmText = stringResource(Res.string.provider_setup_guest_confirm),
        dismissText = stringResource(Res.string.provider_setup_cancel),
        onConfirm = controller::confirmGuestSignIn,
        onDismiss = controller::dismissGuestPrompt,
    )
}

/**
 * The preview: what the held code will add, which profile gets it, and one gold "Add to <profile>". Return
 * confirms, Esc cancels back to the entry field. Refusals (expired, used, network, rate limit) are plain
 * sentences with a way forward. Nothing has been added yet, and the code itself is never shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IptvSetupPreviewPane(
    ui: SetupCodeUiState,
    controller: SetupCodeController,
    profileState: ProfileState,
    auth: AuthState,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    // Keyed on the held-code generation: a link (or a new Continue) while this pane is open restarts the load.
    LaunchedEffect(ui.holdGeneration) { controller.loadPreview() }
    GuestSignInDialog(ui, controller)
    val focus = remember { FocusRequester() }
    LaunchedEffect(ui.previewLoading, ui.previewOutcome) { runCatching { focus.requestFocus() } }

    val preview = ui.preview
    val outcome = ui.previewOutcome
    val selected = profileState.profiles.firstOrNull { it.profileIndex == ui.selectedProfileIndex } ?: profileState.activeProfile
    val dead = ui.redeemRefusal is SetupCodeOutcome.Unusable || ui.redeemRefusal is SetupCodeOutcome.Expired
    val canConfirm = preview != null && !ui.redeeming && !dead

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 4.dp, bottom = 24.dp)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { if (canConfirm) controller.confirm(); true }
                    Key.Escape -> { if (!ui.redeeming) { controller.cancel(); onCancelled() }; true }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NuvioTokens.Radius.xl)).background(tokens.colors.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                ui.previewLoading || (outcome == null && controller.hasHeldCode()) -> Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = tokens.colors.accent)
                    Text(stringResource(Res.string.provider_setup_preview_loading), style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary)
                }
                preview != null -> {
                    Text(stringResource(Res.string.provider_setup_preview_label), style = MaterialTheme.typography.labelMedium, color = tokens.colors.accent, fontWeight = FontWeight.Bold)
                    Text(preview.providerName, style = MaterialTheme.typography.headlineMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
                    if (preview.packageName.isNotBlank()) Text(preview.packageName, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                    DesktopContactButtons(preview.support.links())

                    SectionTitle(stringResource(Res.string.provider_setup_preview_playlists))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        preview.playlists.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.Link, null, tint = tokens.colors.accent, modifier = Modifier.size(18.dp))
                                Text(p.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.Medium)
                                RibbonChip(stringResource(sourceTypeResource(p.sourceType)))
                            }
                        }
                        // Store builds hide add-ons everywhere (AddonSourcePolicy); a full build names them.
                        if (AppFeaturePolicy.addonsEnabled && preview.addons.isNotEmpty()) {
                            Text(
                                stringResource(Res.string.provider_setup_preview_addons) + ": " + preview.addons.joinToString(", "),
                                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
                            )
                        }
                    }

                    SectionTitle(stringResource(Res.string.provider_setup_preview_profile))
                    if (profileState.profiles.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            profileState.profiles.forEach { p ->
                                FilterChip(
                                    selected = p.profileIndex == selected?.profileIndex,
                                    onClick = { controller.selectProfile(p.profileIndex) },
                                    label = { Text(p.name.ifBlank { "#${p.profileIndex}" }) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.selected),
                                        selectedLabelColor = tokens.colors.accent,
                                    ),
                                )
                            }
                        }
                    }
                    (auth as? AuthState.Authenticated)?.email?.let {
                        Text(stringResource(Res.string.provider_setup_preview_account) + ": " + it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                    }
                    ui.redeemRefusal?.let { refusal ->
                        Text(refusal.message?.text().orEmpty(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
                        if (refusal is SetupCodeOutcome.Expired) DesktopContactButtons(refusal.support.links())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = controller::confirm,
                            enabled = canConfirm,
                            shape = tokens.shapes.button,
                            colors = ButtonDefaults.buttonColors(containerColor = tokens.colors.accent, contentColor = tokens.colors.onAccent),
                        ) {
                            Text(
                                if (ui.redeeming) stringResource(Res.string.provider_setup_adding)
                                else stringResource(Res.string.provider_setup_add_to, selected?.name.orEmpty()),
                            )
                        }
                        TextButton(onClick = { controller.cancel(); onCancelled() }, enabled = !ui.redeeming) {
                            Text(stringResource(Res.string.provider_setup_cancel), color = tokens.colors.textSecondary)
                        }
                    }
                }
                else -> {
                    val refused = outcome ?: SetupCodeOutcome.Problem(SetupCodeProblem.EMPTY)
                    val retryable = refused is SetupCodeOutcome.Network || refused is SetupCodeOutcome.RateLimited
                    Text(refused.message?.text().orEmpty(), style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
                    if (refused is SetupCodeOutcome.Expired) DesktopContactButtons(refused.support.links())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (retryable) {
                            Button(
                                onClick = { controller.loadPreview(force = true) },
                                shape = tokens.shapes.button,
                                colors = ButtonDefaults.buttonColors(containerColor = tokens.colors.accent, contentColor = tokens.colors.onAccent),
                            ) { Text(stringResource(Res.string.action_retry)) }
                        }
                        TextButton(onClick = { controller.cancel(); onCancelled() }) {
                            Text(stringResource(Res.string.provider_setup_enter_other_code), color = tokens.colors.accent)
                        }
                    }
                }
            }
        }
    }
}
