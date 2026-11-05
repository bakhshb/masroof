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
        PersonalReadinessRings(snapshot)
    }
}

/** Progress toward the personal model's fixed history requirements, as rings. */
@Composable internal fun PersonalReadinessRings(snapshot: AnalysisSnapshot) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        snapshot.quality.consecutiveCompleteDays?.let { count ->
            com.baraa.masroof.ui.components.MasroofProgressRing(fraction(count.toLong(), PERSONAL_DAYS.toLong()),
                AppDigits.display(stringResource(R.string.analysis_progress, count, PERSONAL_DAYS)), stringResource(R.string.analysis_personal_days))
        }
        com.baraa.masroof.ui.components.MasroofProgressRing(fraction(snapshot.completedPeriods.toLong(), PERSONAL_PERIODS.toLong()),
            AppDigits.display(stringResource(R.string.analysis_progress, snapshot.completedPeriods, PERSONAL_PERIODS)),
            stringResource(R.string.analysis_personal_periods), color = com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.card)
    }
}

internal const val PERSONAL_DAYS = com.baraa.masroof.analytics.PurchaseForecaster.MIN_PERSONAL_DAYS
internal const val PERSONAL_PERIODS = com.baraa.masroof.analytics.PurchaseForecaster.MIN_PERSONAL_PERIODS

/** Average error per horizon as bars; the badge says whether estimates usually ran low or high. */
@Composable internal fun ForecastErrorBars(evaluations: List<AnalysisEvaluation>) {
    if (evaluations.isEmpty()) { Text(stringResource(R.string.lab_analysis_evaluation_missing), style = MaterialTheme.typography.bodySmall); return }
    val maximum = evaluations.maxOf { it.mae }
    evaluations.forEach { e ->
        com.baraa.masroof.ui.components.MasroofValueBarRow(AppDigits.display(stringResource(R.string.analysis_error_horizon, e.horizon)),
            AppDigits.display(stringResource(R.string.analysis_error_value, visualMoney(e.mae))), fraction(e.mae, maximum),
            Modifier.testTag("forecast-error-${e.horizon}"),
            // A zero bias ran neither low nor high.
            subtitle = if (e.bias == 0L) null else AppDigits.display(stringResource(if (e.bias > 0) R.string.analysis_error_low else R.string.analysis_error_high,
                e.count, visualMoney(kotlin.math.abs(e.bias)))),
            style = com.baraa.masroof.ui.components.MasroofHorizontalBarStyle.Default)
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
            if (explanation.underestimates) MasroofBadge(stringResource(R.string.analysis_forecast_runs_low), MasroofCardAccent.Liability,
                Modifier.testTag("forecast-runs-low"))
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
        ForecastErrorBars(explanation.evaluations)
    }
}

@Composable internal fun AnalysisPerformanceOverview(state: AnalysisUiState, workflow: AnalyticsWorkflow) {
    val explanation = workflow.comparisonExplanation()
    MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofGuidanceLabel(stringResource(R.string.learning_forecast_title), listOf(stringResource(R.string.learning_forecast_hint),
                stringResource(R.string.lab_analysis_history_hint)).joinToString("\n\n"),
                style = MaterialTheme.typography.titleMedium, contentColor = MaterialTheme.colorScheme.onSurface)
            if (explanation.status != ForecastComparisonStatus.READY) {
                Text(stringResource(R.string.analysis_comparison_empty), style = MaterialTheme.typography.titleSmall)
                // Steps in time order: recording on, comparison window open, personal model ready.
                explanation.enabledDay?.let { MasroofTimelineRow(AppDigits.display(stringResource(R.string.analysis_enabled_date, day(it))), "") }
                explanation.anchorDay?.let { MasroofTimelineRow(AppDigits.display(stringResource(R.string.analysis_anchor_date, day(it))), "",
                    active = explanation.status != ForecastComparisonStatus.WAITING_FOR_ANCHOR) }
                state.snapshot?.let { s -> MasroofTimelineRow(stringResource(R.string.analysis_personal_readiness),
                    AppDigits.display(listOfNotNull(s.quality.consecutiveCompleteDays?.let { stringResource(R.string.analysis_progress, it, PERSONAL_DAYS) },
                        stringResource(R.string.analysis_progress, s.completedPeriods, PERSONAL_PERIODS)).joinToString(" · ")),
                    active = s.method != "WEEKDAY_MEDIAN") }
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
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofGuidanceLabel(stringResource(R.string.personal_forecast_errors), stringResource(R.string.lab_analysis_evaluation_hint),
                style = MaterialTheme.typography.titleMedium, contentColor = MaterialTheme.colorScheme.onSurface)
            ForecastErrorBars(evaluations)
        }
    }
}
