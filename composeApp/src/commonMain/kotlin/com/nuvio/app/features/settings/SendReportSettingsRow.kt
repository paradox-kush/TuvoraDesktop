package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.ui.reportCategoryLabel
import com.nuvio.app.core.telemetry.ui.reportStatusText
import com.nuvio.app.core.telemetry.ui.rememberReportSender
import com.nuvio.app.core.ui.Chip
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.report_pick_description
import nuvio.composeapp.generated.resources.report_pick_title
import org.jetbrains.compose.resources.stringResource

/** Settings → Diagnostics → "Send a report". No context here, so the user picks the category. */
@Composable
internal fun SendReportSettingsRow(isTablet: Boolean) {
    val sender = rememberReportSender()
    SettingsChipRow(
        title = stringResource(Res.string.report_pick_title),
        description = stringResource(Res.string.report_pick_description),
        isTablet = isTablet,
        footer = reportStatusText(sender.status),
    ) {
        ReportCategory.entries.forEach { category ->
            Chip(
                label = stringResource(reportCategoryLabel(category)),
                selected = false,
                onClick = { sender.send(category, ReportSurface.SETTINGS) },
                enabled = !sender.sending,
            )
        }
    }
}
