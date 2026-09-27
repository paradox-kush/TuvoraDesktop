package com.nuvio.app.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.ui.NuvioStatusModal
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.account_session_lost_later
import nuvio.composeapp.generated.resources.account_session_lost_message
import nuvio.composeapp.generated.resources.account_session_lost_title
import nuvio.composeapp.generated.resources.account_switch_confirm
import nuvio.composeapp.generated.resources.account_switch_keep
import nuvio.composeapp.generated.resources.account_switch_message
import nuvio.composeapp.generated.resources.account_switch_title
import nuvio.composeapp.generated.resources.account_switch_unknown_account
import nuvio.composeapp.generated.resources.settings_account_sign_in
import org.jetbrains.compose.resources.stringResource

/**
 * App-level prompts for the two D1 states: a session that was lost (data kept, sign in again) and a
 * different account signing in over that kept data (ask; never merge it into the new account).
 */
@Composable
internal fun AccountSessionPrompts() {
    val sessionLostNotice by AuthRepository.sessionLostNotice.collectAsStateWithLifecycle()
    val switchPrompt by AuthRepository.accountSwitchPrompt.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var switchBusy by remember { mutableStateOf(false) }
    val unknownAccount = stringResource(Res.string.account_switch_unknown_account)

    val prompt = switchPrompt
    if (prompt != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.account_switch_title),
            message = stringResource(
                Res.string.account_switch_message,
                prompt.previousOwner.email ?: unknownAccount,
                prompt.newEmail ?: unknownAccount,
            ),
            isVisible = true,
            isBusy = switchBusy,
            confirmText = stringResource(Res.string.account_switch_confirm),
            dismissText = stringResource(Res.string.account_switch_keep),
            onConfirm = {
                if (switchBusy) return@NuvioStatusModal
                switchBusy = true
                scope.launch {
                    try {
                        AuthRepository.confirmAccountSwitch()
                    } finally {
                        switchBusy = false
                    }
                }
            },
            onDismiss = {
                if (switchBusy) return@NuvioStatusModal
                switchBusy = true
                scope.launch {
                    try {
                        AuthRepository.cancelAccountSwitch()
                    } finally {
                        switchBusy = false
                    }
                }
            },
        )
        return
    }

    NuvioStatusModal(
        title = stringResource(Res.string.account_session_lost_title),
        message = stringResource(Res.string.account_session_lost_message),
        isVisible = sessionLostNotice != null,
        confirmText = stringResource(Res.string.settings_account_sign_in),
        dismissText = stringResource(Res.string.account_session_lost_later),
        onConfirm = {
            AuthRepository.acknowledgeSessionLostNotice()
            AuthRepository.requestSignIn()
        },
        onDismiss = { AuthRepository.acknowledgeSessionLostNotice() },
    )
}
