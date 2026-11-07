package com.nuvio.app.core.telemetry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportContext
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import com.nuvio.app.core.telemetry.api.formatSupportCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.report_category_app_closed
import nuvio.composeapp.generated.resources.report_category_black_screen
import nuvio.composeapp.generated.resources.report_category_freezes
import nuvio.composeapp.generated.resources.report_category_other
import nuvio.composeapp.generated.resources.report_category_wont_play
import nuvio.composeapp.generated.resources.report_consent_off
import nuvio.composeapp.generated.resources.report_failed
import nuvio.composeapp.generated.resources.report_rate_limited
import nuvio.composeapp.generated.resources.report_sending
import nuvio.composeapp.generated.resources.report_sent
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Where a "Send report" press stands. Every outcome is distinct; none is shown as success but Sent. */
sealed interface ReportStatus {
    data object Idle : ReportStatus
    data object Sending : ReportStatus
    data class Done(val result: ReportResult) : ReportStatus
}

/** Drives one report button. The button must be disabled while [status] is [ReportStatus.Sending]. */
@Stable
class ReportSender internal constructor(private val scope: CoroutineScope) {
    var status: ReportStatus by mutableStateOf(ReportStatus.Idle)
        private set

    val sending: Boolean get() = status == ReportStatus.Sending

    fun send(category: ReportCategory, surface: ReportSurface, context: ReportContext = ReportContext()) {
        if (sending) return
        status = ReportStatus.Sending
        scope.launch {
            status = ReportStatus.Done(
                try {
                    TelemetryAccess.port.sendUserReport(category, surface, context)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    ReportResult.Failed
                },
            )
        }
    }
}

@Composable
fun rememberReportSender(): ReportSender {
    val scope = rememberCoroutineScope()
    return remember(scope) { ReportSender(scope) }
}

/** The confirmation / error line for [status], or null while idle. */
@Composable
fun reportStatusText(status: ReportStatus): String? = when (status) {
    ReportStatus.Idle -> null
    ReportStatus.Sending -> stringResource(Res.string.report_sending)
    is ReportStatus.Done -> when (val r = status.result) {
        is ReportResult.Sent -> stringResource(Res.string.report_sent, formatSupportCode(r.supportCode))
        ReportResult.ConsentOff -> stringResource(Res.string.report_consent_off)
        is ReportResult.RateLimited -> stringResource(Res.string.report_rate_limited)
        ReportResult.Failed -> stringResource(Res.string.report_failed)
    }
}

fun reportCategoryLabel(category: ReportCategory): StringResource = when (category) {
    ReportCategory.WONT_PLAY -> Res.string.report_category_wont_play
    ReportCategory.BLACK_SCREEN -> Res.string.report_category_black_screen
    ReportCategory.FREEZES -> Res.string.report_category_freezes
    ReportCategory.APP_CLOSED -> Res.string.report_category_app_closed
    ReportCategory.OTHER -> Res.string.report_category_other
}
