package com.nuvio.app.features.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_confirm_detach_button
import nuvio.composeapp.generated.resources.provider_confirm_detach_message
import nuvio.composeapp.generated.resources.provider_confirm_detach_title
import nuvio.composeapp.generated.resources.provider_confirm_field_label
import nuvio.composeapp.generated.resources.provider_confirm_remove_button
import nuvio.composeapp.generated.resources.provider_confirm_remove_extra
import nuvio.composeapp.generated.resources.provider_confirm_remove_message
import nuvio.composeapp.generated.resources.provider_confirm_remove_title
import nuvio.composeapp.generated.resources.provider_setup_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * The type-the-word guard for Detach and Remove, as a popover hanging off the control that asked (place it
 * INSIDE the anchor's Box: it opens just below it). The word field is focused so the user can just type; the
 * destructive button is inert until [DestructiveConfirmPolicy.isConfirmed]; Return does nothing unless it
 * matches ([DestructiveConfirmPolicy.returnConfirms]); Esc or a click outside cancels; Cancel is the default.
 */
@Composable
internal fun DestructivePopover(
    action: DestructiveAction,
    playlistName: String,
    providerName: String?,
    managed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var typed by remember(action) { mutableStateOf("") }
    val confirmed = DestructiveConfirmPolicy.isConfirmed(action, typed)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val who = providerName?.takeIf { it.isNotBlank() } ?: ""

    Popup(
        alignment = Alignment.BottomEnd,
        offset = IntOffset(0, 8),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.width(360.dp),
            color = tokens.colors.surfacePopover,
            shape = RoundedCornerShape(NuvioTokens.Radius.lg),
            border = BorderStroke(NuvioTokens.Border.thin, tokens.colors.borderDefault),
            shadowElevation = tokens.elevation.modal,
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = when (action) {
                        DestructiveAction.DETACH -> stringResource(Res.string.provider_confirm_detach_title, who)
                        DestructiveAction.REMOVE -> stringResource(Res.string.provider_confirm_remove_title, playlistName)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    text = when (action) {
                        DestructiveAction.DETACH -> stringResource(Res.string.provider_confirm_detach_message, who)
                        DestructiveAction.REMOVE -> stringResource(Res.string.provider_confirm_remove_message)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
                if (action == DestructiveAction.REMOVE && managed) {
                    Text(
                        text = stringResource(Res.string.provider_confirm_remove_extra, who),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                // Return never confirms on its own: only a matching word does.
                                Key.Enter, Key.NumPadEnter -> { if (DestructiveConfirmPolicy.returnConfirms(action, typed)) onConfirm(); true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.provider_confirm_field_label, action.word)) },
                    shape = RoundedCornerShape(NuvioTokens.Radius.md),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (confirmed) tokens.colors.danger else tokens.colors.borderFocus,
                        unfocusedBorderColor = tokens.colors.borderDefault,
                        focusedContainerColor = tokens.colors.surfaceCard,
                        unfocusedContainerColor = tokens.colors.surfaceCard,
                        cursorColor = tokens.colors.accent,
                    ),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.colors.surfaceCard, contentColor = tokens.colors.textPrimary),
                    ) { Text(stringResource(Res.string.provider_setup_cancel)) }
                    OutlinedButton(
                        onClick = onConfirm,
                        enabled = confirmed,
                        border = BorderStroke(NuvioTokens.Border.thin, if (confirmed) tokens.colors.danger else tokens.colors.borderSubtle),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = tokens.colors.danger, disabledContentColor = tokens.colors.textDisabled,
                        ),
                    ) {
                        Text(
                            stringResource(
                                if (action == DestructiveAction.DETACH) Res.string.provider_confirm_detach_button
                                else Res.string.provider_confirm_remove_button,
                            ),
                        )
                    }
                }
            }
        }
    }
}
