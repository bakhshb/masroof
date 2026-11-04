package com.baraa.masroof.presentation.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.baraa.masroof.R
import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.application.locale.AppDigits
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.ui.components.MasroofForecastSplit
import com.baraa.masroof.ui.components.MasroofDailyBar
import com.baraa.masroof.ui.components.MasroofDailyBarChart
import com.baraa.masroof.ui.components.MasroofTimelineRow
import com.baraa.masroof.ui.theme.MasroofSpacing
import java.time.LocalDate

@Composable internal fun AnalysisQualityEntry(snapshot: AnalysisSnapshot, onOpen: () -> Unit) {
    MasroofCard(Modifier.clickable(onClick = onOpen).testTag("analysis-quality-entry")) {
        MasroofSectionHeader(stringResource(qualityStatusRes(snapshot)),
            onViewAll = onOpen, viewAllLabel = stringResource(R.string.analysis_quality_open))
        Text(stringResource(R.string.lab_analysis_quality_coverage, snapshot.quality.coveredDays, snapshot.completedPeriods), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun ForecastTab(state: AnalysisUiState, workflow: AnalyticsWorkflow, onQuality: () -> Unit, onPerformance: () -> Unit) {
    val s = state.snapshot ?: return
    val explanation = remember(s) { AnalysisPresentationBuilder.forecast(s) }
    MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(if (state.updating) R.string.lab_analysis_updating else
                    forecastMethodResource(if (state.unavailable) ForecastMethod.UNAVAILABLE else explanation.method)), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                MasroofGuidanceButton(stringResource(R.string.personal_forecast_why),
                    forecastHelp(s, state.updating || state.unavailable), Modifier.testTag("forecast-explanation-open"))
            }
            s.total?.let { Amount(stringResource(R.string.lab_analysis_expected_total), it, MasroofAmountRole.Hero) }
            s.remaining?.let { remaining -> MasroofForecastSplit(analysisAmount(s.actual).amount, analysisAmount(remaining).amount,
                AppDigits.display("${stringResource(R.string.lab_analysis_actual)} ${visualMoney(s.actual)} · ${stringResource(R.string.lab_analysis_remaining)} ${visualMoney(remaining)}")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                Column(Modifier.weight(1f)) { Amount(stringResource(R.string.lab_analysis_actual), s.actual) }
                s.remaining?.let { remaining -> Column(Modifier.weight(1f)) { Amount(stringResource(R.string.lab_analysis_remaining), remaining) } }
            }
            state.preferences.budget?.let { Amount(stringResource(R.string.lab_analysis_budget), it) }
            if (workflow.budgetStatus() == "NEAR") Text(stringResource(R.string.lab_analysis_near), style = MaterialTheme.typography.bodySmall)
        }
    }
    val points = remember(s) { AnalysisPresentationBuilder.dailyBars(s) }
    if (points.isNotEmpty()) MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
            MasroofGuidanceLabel(stringResource(R.string.lab_analysis_chart), stringResource(R.string.lab_analysis_chart_hint), style = MaterialTheme.typography.titleMedium, contentColor = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                Text(stringResource(R.string.analysis_actual_legend), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.analysis_expected_legend), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.lab_analysis_sar), style = MaterialTheme.typography.labelSmall)
            }
            var selected by rememberSaveable(s.periodStart) { mutableIntStateOf(0) }
            val bars = points.map { p -> MasroofDailyBar(p.amount?.let { analysisAmount(it).amount }, p.expected,
                AppDigits.display(if (p.amount == null) "${day(p.day)} · ${stringResource(R.string.lab_analysis_period_unavailable)}" else "${day(p.day)} · ${stringResource(if (p.expected) R.string.lab_analysis_estimated else R.string.lab_analysis_actual)} · ${visualMoney(p.amount)}"),
                AppDigits.display(LocalDate.ofEpochDay(p.day).dayOfMonth.toString())) }
            Text(bars[selected.coerceIn(bars.indices)].label, style = MaterialTheme.typography.bodySmall)
            MasroofDailyBarChart(bars, selected, { selected = it }, stringResource(R.string.chart_previous_point), stringResource(R.string.chart_next_point),
                { AppDigits.display(it.stripTrailingZeros().toPlainString()) }, Modifier.testTag("analysis-daily-bars"))
            if (s.wholeDaysFrom != null && s.wholeDaysLower != null && s.wholeDaysUpper != null) MasroofGuidanceLabel(
                stringResource(R.string.lab_analysis_whole_days_range, day(s.wholeDaysFrom), visualMoney(s.wholeDaysLower), visualMoney(s.wholeDaysUpper)),
                stringResource(R.string.lab_analysis_intraday_hint), style = MaterialTheme.typography.bodySmall)
            else if (s.total != null) MasroofGuidanceLabel(stringResource(R.string.lab_analysis_range_unavailable), stringResource(R.string.lab_analysis_no_range))
        }
    }
    AnalysisQualityEntry(s, onQuality)
    MasroofCard(Modifier.clickable(onClick = onPerformance).testTag("analysis-performance-entry")) {
        MasroofSectionHeader(stringResource(R.string.analysis_performance_open), icon = MasroofIcons.analysis,
            onViewAll = onPerformance, viewAllLabel = stringResource(R.string.lab_analysis_open))
        Text(stringResource(R.string.analysis_performance_subtitle), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.lab_analysis_updated, timestamp(s.issuedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun AnalysisPerformanceOverview(state: AnalysisUiState, workflow: AnalyticsWorkflow) {
    val explanation = workflow.comparisonExplanation()
    MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofSectionHeader(stringResource(R.string.learning_forecast_title))
            if (explanation.status != ForecastComparisonStatus.READY) {
                Text(stringResource(R.string.analysis_comparison_empty), style = MaterialTheme.typography.titleSmall)
                explanation.anchorDay?.let { MasroofTimelineRow(stringResource(R.string.analysis_anchor_date, day(it)), stringResource(R.string.learning_forecast_hint), active = false) }
                explanation.enabledDay?.let { MasroofTimelineRow(stringResource(R.string.analysis_enabled_date, day(it)), stringResource(R.string.lab_analysis_history_hint)) }
                val reason = when (explanation.status) {
                    ForecastComparisonStatus.AWAITING_ELIGIBLE_FORECAST -> R.string.analysis_wait_eligible
                    ForecastComparisonStatus.WAITING_FOR_ANCHOR -> R.string.analysis_wait_anchor
                    ForecastComparisonStatus.WAITING_FOR_PERIOD -> R.string.analysis_wait_period
                    ForecastComparisonStatus.INCOMPLETE_COVERAGE -> R.string.analysis_comparison_gap
                    else -> R.string.analysis_no_early_forecast
                }
                Text(stringResource(reason), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    ForecastPerformanceDetails(state)
    state.snapshot?.let { ForecastEvaluationSummary(it) }
    PublishedForecastHistory(state)
}

private fun forecastMethodResource(method: ForecastMethod): Int = when (method) {
    ForecastMethod.INITIAL -> R.string.personal_forecast_initial
    ForecastMethod.PERSONAL -> R.string.personal_forecast_model
    ForecastMethod.UNAVAILABLE -> R.string.personal_forecast_unavailable
}

@Composable private fun forecastHelp(snapshot: AnalysisSnapshot, stale: Boolean): String {
    val explanation = AnalysisPresentationBuilder.forecast(snapshot)
    return listOfNotNull(
        if (stale) stringResource(R.string.personal_forecast_refreshing) else null,
        if (explanation.method == ForecastMethod.UNAVAILABLE) null else stringResource(
            if (explanation.method == ForecastMethod.PERSONAL) R.string.personal_forecast_model_hint else R.string.personal_forecast_initial_hint),
        stringResource(forecastReasonResource(snapshot.modelReason)),
        stringResource(R.string.personal_forecast_qualification),
        stringResource(R.string.lab_analysis_scope_short),
    ).joinToString("\n\n")
}

@Composable private fun ForecastEvaluationSummary(snapshot: AnalysisSnapshot) {
    val evaluations = remember(snapshot) { AnalysisPresentationBuilder.forecast(snapshot).evaluations }
    MasroofCard(Modifier.testTag("forecast-horizon-errors")) {
        MasroofSectionHeader(stringResource(R.string.personal_forecast_errors))
        if (evaluations.isEmpty()) Text(stringResource(R.string.lab_analysis_evaluation_missing))
        evaluations.forEach { e -> Text(stringResource(R.string.personal_forecast_error_row, e.horizon, visualMoney(e.mae), e.count)) }
    }
}
