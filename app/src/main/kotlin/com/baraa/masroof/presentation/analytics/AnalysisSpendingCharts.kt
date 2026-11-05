package com.baraa.masroof.presentation.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.baraa.masroof.R
import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.application.locale.AppDigits
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.ui.components.MasroofValueBarRow
import com.baraa.masroof.ui.components.MasroofDivergingBarRow
import com.baraa.masroof.ui.components.MasroofAnalyticsChartMath
import com.baraa.masroof.ui.theme.MasroofSpacing
import java.math.BigDecimal

@Composable internal fun visualMoney(value: Long) = formatLocalizedMoney(analysisAmount(value))
internal fun fraction(value: Long, maximum: Long) = com.baraa.masroof.ui.components.MasroofBarChart.progress(BigDecimal.valueOf(value), BigDecimal.valueOf(maximum))

@Composable internal fun AnalysisSpendingObservations(state: AnalysisUiState, onOpenGroup: (String) -> Unit) {
    if (state.updating || state.unavailable || state.observations.isEmpty()) return
    MasroofCard(Modifier.testTag("spending-observations")) {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofGuidanceLabel(stringResource(R.string.personal_spending_attention), stringResource(R.string.personal_spending_hint),
                style = MaterialTheme.typography.titleMedium)
            state.observations.forEach { observation ->
                Column(Modifier.testTag("spending-observation-chart-${observation.id}").clickable(
                    role = Role.Button, onClickLabel = stringResource(R.string.personal_spending_evidence),
                    onClick = { onOpenGroup("observation:${observation.id}") }),
                    verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                    Text(observation.merchant, style = MaterialTheme.typography.titleSmall)
                    AnalysisSpendingObservationBars(observation)
                }
            }
        }
    }
}

@Composable internal fun AnalysisSpendingObservationBars(observation: AnalysisSpendingObservation) {
    val maximum = maxOf(observation.amount, observation.reference)
    MasroofValueBarRow(stringResource(R.string.personal_spending_payment), visualMoney(observation.amount),
        fraction(observation.amount, maximum), Modifier.testTag("spending-observation-current-${observation.id}"),
        subtitle = AppDigits.display(day(observation.day)))
    MasroofValueBarRow(stringResource(R.string.personal_spending_median), visualMoney(observation.reference),
        fraction(observation.reference, maximum), Modifier.testTag("spending-observation-reference-${observation.id}"), previous = true,
        subtitle = AppDigits.display(stringResource(R.string.personal_spending_sample, observation.referenceIds.size)))
}

/** Actual spending so far, with the equal-length comparison drawn as two bars and a direction badge. */
@Composable internal fun AnalysisSpendingHero(snapshot: AnalysisSnapshot) {
    val comparison = snapshot.insights?.comparison
    MasroofCard(Modifier.testTag("analysis-spending-hero")) {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofGuidanceLabel(stringResource(R.string.lab_analysis_actual), listOfNotNull(stringResource(R.string.lab_analysis_scope_short),
                comparison?.let { stringResource(R.string.lab_analysis_comparison_dates, day(it.from), day(it.through), day(it.previousFrom), day(it.previousThrough)) }
            ).joinToString("\n\n"))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                MasroofAmountText(visualMoney(snapshot.actual), MasroofAmountRole.Hero, Modifier.weight(1f))
                comparison?.percent?.let { percent ->
                    val down = comparison.delta < 0
                    MasroofBadge(AppDigits.display(stringResource(if (down) R.string.analysis_change_down else R.string.analysis_change_up, percent.removePrefix("-"))),
                        if (down) MasroofCardAccent.Inflow else MasroofCardAccent.Outflow)
                }
            }
            if (comparison == null) Text(stringResource(R.string.lab_analysis_comparison_missing), style = MaterialTheme.typography.bodySmall)
            else {
                val maximum = maxOf(comparison.current, comparison.previous, 0)
                MasroofValueBarRow(stringResource(R.string.lab_analysis_comparison_current), visualMoney(comparison.current),
                    fraction(comparison.current, maximum), style = com.baraa.masroof.ui.components.MasroofHorizontalBarStyle.Default)
                MasroofValueBarRow(stringResource(R.string.lab_analysis_comparison_previous), visualMoney(comparison.previous),
                    fraction(comparison.previous, maximum), previous = true, style = com.baraa.masroof.ui.components.MasroofHorizontalBarStyle.Default)
            }
        }
    }
}

/** One stacked bar for the parts of spending; each row opens that part's transactions. */
@Composable internal fun AnalysisComponentBars(snapshot: AnalysisSnapshot, onOpenGroup: (String) -> Unit) {
    val groups = snapshot.insights?.components.orEmpty()
    val parts = remember(snapshot) { AnalysisPresentationBuilder.composition(snapshot) }
    val colors = compositionColors()
    Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        MasroofSectionHeader(stringResource(R.string.analysis_composition))
        val description = parts.map { "${spendingGroupName(it.group)} ${stringResource(R.string.analysis_share_percent, it.percent)}" }.joinToString(" · ")
        com.baraa.masroof.ui.components.MasroofStackedBar(parts.map { com.baraa.masroof.ui.components.MasroofStackedPart(it.share, colors.getValue(it.group.key)) },
            AppDigits.display(description))
        Column {
            groups.forEach { group ->
                val part = parts.firstOrNull { it.group.key == group.key }
                Row(Modifier.fillMaxWidth().heightIn(min = MasroofSpacing.minimumTouchTarget)
                    .clickable(role = Role.Button, onClick = { onOpenGroup(group.key) }).testTag("analysis-component-${group.key}"),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                    Box(Modifier.size(MasroofSpacing.sectionGap).background(if (part != null) colors.getValue(group.key) else
                        MaterialTheme.colorScheme.surfaceVariant, com.baraa.masroof.ui.theme.MasroofComponentShapes.tokenSwatch))
                    Text(spendingGroupName(group), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    part?.let { Text(AppDigits.display(stringResource(R.string.analysis_share_percent, it.percent)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(visualMoney(group.amount), style = MaterialTheme.typography.titleSmall,
                        color = if (group.amount < 0) com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.inflow else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable private fun compositionColors(): Map<String, androidx.compose.ui.graphics.Color> {
    val extended = com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors
    return mapOf("PURCHASES" to extended.card, "CASH" to extended.liability, "TRANSFERS" to extended.account, "REFUNDS" to extended.inflow)
}

@Composable internal fun AnalysisChangeCard(snapshot: AnalysisSnapshot, onOpenGroup: (String) -> Unit) {
    val changes = snapshot.insights?.comparison?.changes.orEmpty()
    if (changes.isEmpty()) return
    val maximum = changes.maxOf { BigDecimal.valueOf(it.delta).abs() }
    MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofGuidanceLabel(stringResource(R.string.analysis_change_title), stringResource(R.string.lab_analysis_changes_hint), style = MaterialTheme.typography.titleMedium)
            changes.forEach { change ->
                val magnitude = BigDecimal.valueOf(change.delta).abs()
                val label = stringResource(if (change.delta < 0) R.string.analysis_decrease else R.string.analysis_increase,
                    formatLocalizedMoney(analysisAmount(magnitude.movePointLeft(2))))
                MasroofDivergingBarRow(AppDigits.display(spendingGroupName(change.group)), AppDigits.display(label),
                    MasroofAnalyticsChartMath.signedFraction(BigDecimal.valueOf(change.delta), maximum),
                    Modifier.testTag("analysis-change-${change.group.key}").clickable { onOpenGroup("change:${change.group.key}") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.analysis_increase_axis), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.analysis_decrease_axis), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

@Composable internal fun AnalysisMerchantCard(snapshot: AnalysisSnapshot, onOpenGroup: (String) -> Unit,
    onViewAll: (() -> Unit)? = null, showAll: Boolean = false) {
    val merchants = snapshot.insights?.merchants.orEmpty()
    MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                MasroofSectionHeader(stringResource(R.string.analysis_merchants), Modifier.weight(1f), icon = MasroofIcons.merchant,
                    onViewAll = onViewAll, viewAllLabel = stringResource(R.string.dashboard_merchants_view_all))
                MasroofGuidanceButton(stringResource(R.string.analysis_merchants), stringResource(R.string.analysis_merchants_scope))
            }
            if (merchants.isEmpty()) Text(stringResource(R.string.lab_analysis_no_merchants))
            (if (showAll) merchants else merchants.take(5)).forEach { group ->
                MasroofValueBarRow(AppDigits.display(group.name.orEmpty()), visualMoney(group.amount), fraction(group.amount, merchants.first().amount),
                    modifier = Modifier.testTag("analysis-merchant-${group.key}"),
                    subtitle = AppDigits.display(stringResource(R.string.analysis_merchant_count, group.ids.size)), onClick = { onOpenGroup(group.key) },
                    style = com.baraa.masroof.ui.components.MasroofHorizontalBarStyle.Default)
            }
        }
    }
}
