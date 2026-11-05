package com.baraa.masroof.presentation.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.application.locale.AppDigits
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.ui.components.MasroofDueTimeline
import com.baraa.masroof.ui.components.MasroofStackedBar
import com.baraa.masroof.ui.components.MasroofStackedPart
import com.baraa.masroof.ui.components.MasroofTimelineMark
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofThemeExtras

/** Commitments due this period: one total, a due-date line and a nearest-first list. */
@Composable internal fun CommitmentsOverview(snapshot: AnalysisSnapshot, workflow: AnalyticsWorkflow,
    focusedAlert: String?, onFocusConsumed: () -> Unit) {
    val view = remember(snapshot) { workflow.commitmentsView(snapshot) }
    val palette = duePalette()
    MasroofCard(Modifier.testTag("analysis-commitments-total")) {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MasroofGuidanceLabel(stringResource(R.string.analysis_commitments_total), stringResource(R.string.lab_analysis_commitments_hint),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            if (view.rows.isEmpty()) {
                com.baraa.masroof.ui.components.MasroofAnalysisEmptyState(stringResource(R.string.analysis_no_due),
                    stringResource(R.string.analysis_no_due_hint), MasroofIcons.calendar)
            } else if (view.confirmedCount > 0) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                    MasroofAmountText(visualMoney(view.confirmedTotal), MasroofAmountRole.Hero, Modifier.weight(1f, fill = false))
                    Text(AppDigits.display(stringResource(R.string.analysis_commitments_count, view.confirmedCount)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MasroofStackedBar(view.rows.mapIndexed { index, row -> MasroofStackedPart(row.share, palette[index % palette.size]) },
                    stringResource(R.string.analysis_commitments_total))
                val horizon = view.daysToPeriodEnd.coerceAtLeast(1)
                val marks = view.rows.filter { it.daysUntil != null && it.daysUntil in 0..view.daysToPeriodEnd }
                    .groupBy { it.daysUntil!! }.map { (days, rows) -> MasroofTimelineMark(days.toFloat() / horizon, rows.size, days <= DUE_SOON_DAYS) }
                if (marks.isNotEmpty()) MasroofDueTimeline(marks, stringResource(R.string.analysis_due_today),
                    AppDigits.display(day(snapshot.periodEnd - 1)),
                    AppDigits.display(stringResource(R.string.analysis_due_timeline, day(snapshot.periodEnd - 1))))
            }
        }
    }
    view.rows.forEachIndexed { index, row ->
        FocusAnalysisItem(focusedAlert == row.due.alertId, onFocusConsumed) {
            DueRow(row, palette[index % palette.size].takeIf { row.due.confirmed })
        }
    }
}

@Composable private fun duePalette(): List<androidx.compose.ui.graphics.Color> {
    val extended = MasroofThemeExtras.extendedColors
    return listOf(extended.liability, extended.account, extended.card, extended.inflow, MaterialTheme.colorScheme.primary)
}

@Composable private fun DueRow(row: AnalysisDueRow, swatch: androidx.compose.ui.graphics.Color?) {
    val due = row.due
    MasroofCard(Modifier.testTag("analysis-due-${due.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
            swatch?.let { Box(Modifier.size(MasroofSpacing.sectionGap).background(it, com.baraa.masroof.ui.theme.MasroofComponentShapes.tokenSwatch)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.hairlineGap)) {
                Text(AppDigits.display(CommitmentNames.name(LocalContext.current, due.source, due.name, due.loanTypeFallback)),
                    style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(AppDigits.display(due.day?.let { day(it) }.orEmpty()), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
                MasroofAmountText(visualMoney(due.amount), MasroofAmountRole.Card)
                dueBadge(row)?.let { (text, accent) -> MasroofBadge(text, accent) }
            }
        }
    }
}

@Composable private fun dueBadge(row: AnalysisDueRow): Pair<String, MasroofCardAccent>? {
    if (!row.due.confirmed) return stringResource(R.string.lab_analysis_estimated) to MasroofCardAccent.None
    return row.daysUntil?.let { dueCountdown(it) }
}
