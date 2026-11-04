package com.baraa.masroof.presentation.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.application.dashboard.AnalysisSpendingGroup
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.common.MasroofText as Text

@Composable internal fun spendingGroupName(group: AnalysisSpendingGroup): String = group.name ?: stringResource(when (group.key) {
    "CASH" -> R.string.lab_analysis_component_cash
    "TRANSFERS" -> R.string.lab_analysis_component_transfers
    "REFUNDS" -> R.string.lab_analysis_component_refunds
    else -> R.string.lab_analysis_component_purchases
})

@Composable internal fun AnalysisSpendingExplanation(snapshot: AnalysisSnapshot, onOpenGroup: (String) -> Unit) {
    if (snapshot.insights == null) return
    AnalysisComparisonCard(snapshot)
    MasroofCard(Modifier.testTag("analysis-spending-explanation")) {
        AnalysisComponentBars(snapshot, onOpenGroup)
    }
}

/** The data-quality headline shared by the forecast entry card and the quality screen. */
internal fun qualityStatusRes(snapshot: AnalysisSnapshot): Int = when {
    snapshot.quality.blocked -> R.string.lab_analysis_quality_warning
    snapshot.status != "READY" -> R.string.lab_analysis_quality_insufficient
    snapshot.method == "WEEKDAY_MEDIAN" -> R.string.lab_analysis_quality_initial
    else -> R.string.lab_analysis_quality_personal
}

@Composable internal fun AnalysisDataQuality(snapshot: AnalysisSnapshot, workflow: AnalyticsWorkflow, onOpenTransaction: (String) -> Unit,
    onOpenReview: () -> Unit, onRequestSmsPermission: () -> Unit, onRescan: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val quality = snapshot.quality
    MasroofCard(Modifier.testTag("analysis-data-quality")) {
        Column(verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofSpacing.cardInnerGap)) {
            MasroofGuidanceLabel(stringResource(qualityStatusRes(snapshot)), if (snapshot.status != "READY") stringResource(R.string.lab_analysis_need_data) else stringResource(R.string.lab_analysis_quality_hint),
                style = MaterialTheme.typography.titleMedium, contentColor = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.lab_analysis_quality_coverage, quality.coveredDays, snapshot.completedPeriods), style = MaterialTheme.typography.bodySmall)
        }
    }
    MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofSpacing.cardInnerGap)) {
            MasroofSectionHeader(stringResource(R.string.analysis_coverage_title))
            if (quality.coverageDays.isNotEmpty()) com.baraa.masroof.ui.components.MasroofCoverageGrid(quality.coverageDays.map { it.covered },
                stringResource(R.string.lab_analysis_quality_coverage, quality.coveredDays, snapshot.completedPeriods))
            if (quality.historyFrom != null && quality.historyThrough != null) Text(stringResource(R.string.lab_analysis_history_range,
                day(quality.historyFrom), day(quality.historyThrough)), style = MaterialTheme.typography.bodySmall)
            if (!quality.coverageKnown) Text(stringResource(R.string.lab_analysis_coverage_unknown), style = MaterialTheme.typography.bodySmall)
            if (snapshot.trainingFrom != null && snapshot.trainingThrough != null) MasroofGuidanceLabel(stringResource(R.string.lab_analysis_training_range,
                day(snapshot.trainingFrom), day(snapshot.trainingThrough)), stringResource(R.string.lab_analysis_training_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
    MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofSpacing.sectionGap)) {
            val readinessHelp = listOfNotNull(stringResource(R.string.lab_analysis_quality_history_reason),
                stringResource(R.string.forecast_continuous_learning),
                if (snapshot.status == "READY") stringResource(forecastReasonResource(snapshot.modelReason)) else null).joinToString("\n\n")
            MasroofGuidanceLabel(stringResource(R.string.analysis_personal_readiness), readinessHelp,
                style = MaterialTheme.typography.titleMedium, contentColor = MaterialTheme.colorScheme.onSurface)
            quality.consecutiveCompleteDays?.let { count -> com.baraa.masroof.ui.components.MasroofValueBarRow(
                stringResource(R.string.analysis_personal_days), com.baraa.masroof.application.locale.AppDigits.display(stringResource(R.string.analysis_progress, count, 120)), fraction(count.toLong(), 120),
                style = com.baraa.masroof.ui.components.MasroofHorizontalBarStyle.Default) }
            com.baraa.masroof.ui.components.MasroofValueBarRow(stringResource(R.string.analysis_personal_periods),
                com.baraa.masroof.application.locale.AppDigits.display(stringResource(R.string.analysis_progress, snapshot.completedPeriods, 4)), fraction(snapshot.completedPeriods.toLong(), 4),
                style = com.baraa.masroof.ui.components.MasroofHorizontalBarStyle.Default)
        }
    }
    MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofSpacing.cardInnerGap)) {
        if (quality.pendingReviews > 0) MasroofTextButton(onClick = onOpenReview, modifier = Modifier.testTag("analysis-open-review")) {
            Text(stringResource(R.string.lab_analysis_quality_open_review) + " (${quality.pendingReviews})")
        } else Text(stringResource(R.string.lab_analysis_quality_no_reviews), style = MaterialTheme.typography.bodySmall)
        quality.reasons.forEach { reason ->
            when (reason) {
                "SMS_PERMISSION" -> {
                    Text(stringResource(R.string.lab_analysis_quality_permission), style = MaterialTheme.typography.bodySmall)
                    MasroofTextButton(onClick = onRequestSmsPermission) { Text(stringResource(R.string.lab_analysis_quality_permission_action)) }
                }
                "PROCESSING_RETRY" -> {
                    Text(stringResource(R.string.lab_analysis_quality_retry), style = MaterialTheme.typography.bodySmall)
                    MasroofTextButton(onClick = onRescan) { Text(stringResource(R.string.lab_analysis_quality_retry_action)) }
                }
                "IMPORT_PENDING", "IMPORT_RUNNING" -> {
                    Text(stringResource(R.string.lab_analysis_quality_import), style = MaterialTheme.typography.bodySmall)
                    if (reason == "IMPORT_PENDING") MasroofTextButton(onClick = onRescan) { Text(stringResource(R.string.lab_analysis_quality_retry_action)) }
                }
                "COVERAGE_GAPS" -> Text(stringResource(R.string.lab_analysis_quality_gaps), style = MaterialTheme.typography.bodySmall)
            }
        }

            Text(stringResource(R.string.analysis_used_count, quality.used), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.analysis_outside_count, quality.excluded["OUTSIDE_PURCHASE_SCOPE"].orEmpty().size), style = MaterialTheme.typography.bodySmall)
            quality.excluded.filterKeys { it !in setOf("OUTSIDE_PURCHASE_SCOPE", "DUPLICATE_ID") }.forEach { (reason, ids) ->
                EvidenceGroup(ids, stringResource(if (reason == "FUTURE_DATE") R.string.lab_analysis_excluded_future else R.string.lab_analysis_excluded_amount), workflow, snapshot.sourceHash, onOpenTransaction)
            }
            MasroofTextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.lab_analysis_hide_data else R.string.lab_analysis_show_data)) }
            if (expanded) {
                if (quality.unusual.isNotEmpty()) EvidenceGroup(quality.unusual, stringResource(R.string.lab_analysis_unusual), workflow, snapshot.sourceHash, onOpenTransaction)
                quality.excluded["OUTSIDE_PURCHASE_SCOPE"]?.let { EvidenceGroup(it, stringResource(R.string.lab_analysis_excluded_scope), workflow, snapshot.sourceHash, onOpenTransaction) }
                quality.excluded["DUPLICATE_ID"]?.let { EvidenceGroup(it, stringResource(R.string.lab_analysis_excluded_duplicate), workflow, snapshot.sourceHash, onOpenTransaction) }
                MasroofGuidanceLabel(stringResource(R.string.lab_analysis_quality_counts, quality.used, quality.coveredDays, snapshot.completedPeriods, quality.pendingReviews), stringResource(R.string.lab_analysis_quality_counts_hint), style = MaterialTheme.typography.bodySmall)
                MasroofGuidanceLabel(stringResource(R.string.lab_analysis_scope_title), stringResource(R.string.lab_analysis_scope))
                MasroofGuidanceLabel(stringResource(R.string.lab_analysis_evaluation), stringResource(R.string.lab_analysis_evaluation_hint))
                if (snapshot.evaluations.isEmpty()) Text(stringResource(R.string.lab_analysis_evaluation_missing), style = MaterialTheme.typography.bodySmall)
                snapshot.evaluations.forEach { e -> Text(stringResource(R.string.lab_analysis_evaluation_row, e.horizon, e.count, visualMoney(e.mae), visualMoney(e.bias)), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

internal fun forecastReasonResource(reason: String?): Int = when (reason) {
    "PERSONAL_MODEL" -> R.string.lab_analysis_ready
    "NEEDS_EVALUATIONS" -> R.string.lab_analysis_quality_evaluation_reason
    "BASELINE_BETTER" -> R.string.lab_analysis_quality_baseline_reason
    "LIVE_BASELINE_BETTER" -> R.string.learning_forecast_paused
    "MODEL_UNAVAILABLE" -> R.string.lab_analysis_quality_model_retry
    "COVERAGE_GAPS" -> R.string.lab_analysis_quality_gaps
    "NEEDS_PERIODS" -> R.string.lab_analysis_quality_periods_reason
    "NEEDS_PURCHASE_DAYS" -> R.string.lab_analysis_quality_purchase_days_reason
    else -> R.string.lab_analysis_quality_history_reason
}

@Composable internal fun MessageLearningInsights(state: AnalysisUiState) {
    state.learning?.let { learning ->
        MasroofCard(Modifier.testTag("learning-insights")) {
            MasroofGuidanceLabel(stringResource(R.string.learning_title), stringResource(R.string.learning_counts_hint),
                style = MaterialTheme.typography.titleSmall)
            if(learning.pending) Text(stringResource(R.string.learning_pending), style = MaterialTheme.typography.bodySmall)
            else if(learning.statisticalFamilies == 0) Text(stringResource(R.string.learning_statistical_paused), style = MaterialTheme.typography.bodySmall)
            else Text(stringResource(R.string.learning_statistical_active,learning.statisticalFamilies), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.learning_distinct_origins, learning.statisticalSuggestions, learning.templateSuggestions), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (learning.personalUsed) R.string.learning_personal_used else R.string.learning_personal_collecting), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.learning_personal_examples, learning.trainingTemplates, learning.validationTemplates), style = MaterialTheme.typography.bodySmall)
            Text(if (learning.personalReason == "NEEDS_EVALUATIONS") stringResource(R.string.learning_needs_examples_count,
                learning.validationTemplates, learning.requiredValidationTemplates)
                else stringResource(personalLearningReason(learning.personalReason)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.learning_counts, learning.attempted, learning.suggested, learning.understood, learning.posted, learning.awaitingReview), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.learning_feedback_counts, learning.learned, learning.blocked, learning.excluded, learning.repeatedContradictions, learning.pendingDecisions), style = MaterialTheme.typography.bodySmall)
            learning.exclusions.forEach { (reason,count) -> Text(stringResource(R.string.learning_exclusion_count,
                stringResource(com.baraa.masroof.presentation.interpretation.learningReasonResource(reason)),count), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal fun personalLearningReason(reason: String): Int = when (reason) {
    "SEED_NOT_ELIGIBLE" -> R.string.learning_seed_not_eligible
    "SAFETY_VALIDATION_FAILED" -> R.string.learning_personal_safety
    "BASELINE_BETTER" -> R.string.learning_baseline_better
    "PERSONAL_MODEL" -> R.string.learning_personal_used_reason
    else -> R.string.learning_needs_examples
}

@Composable internal fun ForecastPerformanceDetails(state: AnalysisUiState) {
    MasroofCard(Modifier.testTag("forecast-performance")) {
        Column(verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofSpacing.sectionGap)) {
            MasroofGuidanceLabel(stringResource(R.string.learning_forecast_title), stringResource(R.string.learning_forecast_hint), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.learning_forecast_periods, state.forecastPerformance.periods), style = MaterialTheme.typography.bodySmall)
            if (!state.forecastPerformance.allowPersonal) Text(stringResource(R.string.learning_forecast_paused), style = MaterialTheme.typography.bodySmall)
            state.forecastPerformance.personalMae?.let { Amount(stringResource(R.string.learning_forecast_personal_error), it) }
            state.forecastPerformance.baselineMae?.let { Amount(stringResource(R.string.learning_forecast_baseline_error), it) }
            state.forecastComparisons.filter { ForecastPerformancePolicy.supports(it) }.groupBy { it.periodStart }.values.map { it.minBy { row -> row.issuedAt } }.sortedBy { it.periodStart }.takeLast(3).forEach { comparison ->
                Text("${day(comparison.periodStart)} — ${day(comparison.periodEnd - 1)}", style = MaterialTheme.typography.titleSmall)
                Text(timestamp(comparison.issuedAt), style = MaterialTheme.typography.bodySmall)
                com.baraa.masroof.application.analytics.ForecastPerformancePolicy.originDay(comparison)?.let { origin ->
                    Text(stringResource(R.string.learning_forecast_origin, day(origin), (comparison.periodEnd - origin).toInt()), style = MaterialTheme.typography.bodySmall)
                }
                val maximum = maxOf(comparison.personalTotal, comparison.baselineTotal, comparison.actual ?: 0, 0)
                com.baraa.masroof.ui.components.MasroofValueBarRow(stringResource(R.string.learning_forecast_personal), visualMoney(comparison.personalTotal), fraction(comparison.personalTotal, maximum), style = com.baraa.masroof.ui.components.MasroofHorizontalBarStyle.Default)
                com.baraa.masroof.ui.components.MasroofValueBarRow(stringResource(R.string.learning_forecast_baseline), visualMoney(comparison.baselineTotal), fraction(comparison.baselineTotal, maximum), previous = true, style = com.baraa.masroof.ui.components.MasroofHorizontalBarStyle.Default)
                comparison.actual?.let { actual ->
                    com.baraa.masroof.ui.components.MasroofValueBarRow(stringResource(R.string.lab_analysis_final_actual), visualMoney(actual), fraction(actual, maximum))
                } ?: Text(stringResource(R.string.analysis_wait_period), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable internal fun PublishedForecastHistory(state: AnalysisUiState) {
    if (state.history.isEmpty()) return
    val groups = state.forecastHistoryGroups().take(8)
    val current = groups.map { it.snapshots.first() }.filter { it.periodStart == state.snapshot?.periodStart && it.total != null }.sortedBy { it.issuedAt }
    MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofSpacing.cardInnerGap)) {
        if (current.isNotEmpty()) {
            Text(stringResource(R.string.analysis_history_trend), style = MaterialTheme.typography.titleMedium)
            var selected by rememberSaveable { mutableIntStateOf(0) }
            val axes = current.map { timestamp(it.issuedAt) }
            val labels = current.map { "${timestamp(it.issuedAt)} · ${visualMoney(it.total!!)}" }
            Text(labels[selected.coerceIn(labels.indices)], style = MaterialTheme.typography.bodySmall)
            MasroofInteractiveLineChart(current.map { analysisAmount(it.total!!).amount }, java.math.BigDecimal.ZERO,
                selectedPointIndex = selected.coerceIn(current.indices), onPointSelected = { selected = it }, pointLabel = { labels[it] },
                axisLabel = { axes[it] }, currencyLabel = stringResource(R.string.lab_analysis_sar))
        }
        MasroofGuidanceLabel(stringResource(R.string.lab_analysis_history), stringResource(R.string.lab_analysis_history_hint),
            style = MaterialTheme.typography.titleSmall, contentColor = MaterialTheme.colorScheme.onSurface)
        var historyExpanded by rememberSaveable { mutableStateOf(false) }
        MasroofTextButton(onClick = { historyExpanded = !historyExpanded }) { Text(stringResource(R.string.analysis_view_history)) }
        if (historyExpanded) groups.forEach { group ->
            val past = group.snapshots.first()
            var expanded by rememberSaveable(past.issuedAt) { mutableStateOf(false) }
            Text(timestamp(past.issuedAt), style = MaterialTheme.typography.bodySmall)
            past.total?.let { Amount(stringResource(R.string.lab_analysis_expected_total), it) }
            state.historyActuals[past.periodStart]?.let { Amount(stringResource(R.string.lab_analysis_final_actual), it) }
            if (group.snapshots.size > 1) {
                Text(stringResource(R.string.lab_analysis_history_group, group.snapshots.size), style = MaterialTheme.typography.bodySmall)
                MasroofTextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.lab_analysis_history_collapse else R.string.lab_analysis_history_expand)) }
                if (expanded) group.snapshots.forEach { Text(timestamp(it.issuedAt), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    }
}
