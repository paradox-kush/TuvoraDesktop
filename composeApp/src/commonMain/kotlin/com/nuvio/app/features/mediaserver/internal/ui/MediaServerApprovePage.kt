package com.nuvio.app.features.mediaserver.internal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.flow.ApproveCodeController
import com.nuvio.app.features.mediaserver.internal.flow.ApproveStatus
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ms_approve_busy
import nuvio.composeapp.generated.resources.ms_approve_button
import nuvio.composeapp.generated.resources.ms_approve_code_hint
import nuvio.composeapp.generated.resources.ms_approve_intro
import nuvio.composeapp.generated.resources.ms_approve_none
import nuvio.composeapp.generated.resources.ms_approve_status_approved
import nuvio.composeapp.generated.resources.ms_approve_status_invalid
import nuvio.composeapp.generated.resources.ms_approve_status_not_found
import nuvio.composeapp.generated.resources.ms_approve_status_sign_in_again
import nuvio.composeapp.generated.resources.ms_approve_status_unreachable
import org.jetbrains.compose.resources.stringResource

/** "Approve a code": this device authorises the Quick Connect code a TV (or another phone) is showing - Swiftfin's pattern. */
internal fun LazyListScope.mediaServerApproveContent(isTablet: Boolean) {
    item {
        val runtime = remember { MediaServerRuntime.production.also { it.entryStore.ensureLoaded() } }
        val scope = rememberCoroutineScope()
        val controller = remember { ApproveCodeController(runtime.services, { runtime.entryStore.current() }, scope) }
        val state by controller.state.collectAsStateWithLifecycle()
        val tokens = MaterialTheme.nuvio

        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap)) {
            if (state.servers.isEmpty()) {
                Text(text = stringResource(Res.string.ms_approve_none), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                return@Column
            }
            Text(text = stringResource(Res.string.ms_approve_intro), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            if (state.servers.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.servers.forEach { server ->
                        Chip(label = server.name, selected = server.key == state.selectedKey, onClick = { controller.select(server.key) })
                    }
                }
            }
            NuvioInputField(value = state.code, onValueChange = controller::setCode, placeholder = stringResource(Res.string.ms_approve_code_hint))
            val message = when (state.status) {
                ApproveStatus.APPROVED -> stringResource(Res.string.ms_approve_status_approved) to false
                ApproveStatus.INVALID_CODE -> stringResource(Res.string.ms_approve_status_invalid) to true
                ApproveStatus.CODE_NOT_FOUND -> stringResource(Res.string.ms_approve_status_not_found) to true
                ApproveStatus.SIGN_IN_AGAIN -> stringResource(Res.string.ms_approve_status_sign_in_again) to true
                ApproveStatus.UNREACHABLE -> stringResource(Res.string.ms_approve_status_unreachable) to true
                ApproveStatus.IDLE, ApproveStatus.BUSY -> null
            }
            message?.let { (text, isError) ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) tokens.colors.danger else tokens.colors.accent,
                )
            }
            NuvioPrimaryButton(
                text = stringResource(if (state.status == ApproveStatus.BUSY) Res.string.ms_approve_busy else Res.string.ms_approve_button),
                enabled = state.status != ApproveStatus.BUSY && state.code.isNotBlank(),
                onClick = controller::approve,
            )
        }
    }
}
