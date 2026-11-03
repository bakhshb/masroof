package com.baraa.masroof.presentation.dashboard

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.DailySpendingTrend
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofGuidanceLabel
import com.baraa.masroof.presentation.common.MasroofInteractiveLineChart
import com.baraa.masroof.presentation.common.MasroofText
import com.baraa.masroof.presentation.common.MasroofChartAverageLegend
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.ui.theme.MasroofHeroRole
import com.baraa.masroof.ui.theme.MasroofReference
import com.baraa.masroof.ui.theme.MasroofSpacing
import java.time.format.DateTimeFormatter

@Composable
fun SpendingTrendSection(trend: DailySpendingTrend?, title: String, modifier: Modifier = Modifier,
    explanation: String = stringResource(R.string.approved_card_expense_basis),
    role: MasroofHeroRole = MasroofHeroRole.Brand, onDaySelected: ((java.time.LocalDate) -> Unit)? = null,
    selectedDate: java.time.LocalDate? = null, showAverage: Boolean = false,
    controls: (@Composable () -> Unit)? = null, chartKey: String = "home:overall", scopeLabel: String = title) {
    if (trend == null) return
    val workflow = LocalChartDataWorkflow.current
    val preferences by workflow.preferences.collectAsState()
    val selected = workflow.categories(trend, chartKey)
    val custom = preferences.selections.containsKey(chartKey) && selected != trend.categoryTrends.keys
    val labels = selected.map { chartCategoryLabel(it) }.joinToString(stringResource(R.string.chart_type_separator))
    val guidance = if (custom) stringResource(R.string.chart_selected_data, labels) + "\n" +
        stringResource(R.string.chart_refunds_selection_hint) else explanation
    var sheetOpen by rememberSaveable(chartKey) { mutableStateOf(false) }
    if (sheetOpen) ChartDataBottomSheet(trend, chartKey, scopeLabel) { sheetOpen = false }
    SpendingTrendCard(selectedChartTrend(trend, chartKey), title, modifier, guidance, role,
        onDaySelected, selectedDate, showAverage, controls,
        if (trend.categoryTrends.isEmpty()) null else ({ sheetOpen = true }),
        if (custom) stringResource(R.string.chart_custom_selection, selected.size) else null)
}

@Composable
private fun SpendingTrendCard(trend: DailySpendingTrend?, title: String, modifier: Modifier,
    explanation: String, role: MasroofHeroRole, onDaySelected: ((java.time.LocalDate) -> Unit)?,
    selectedDate: java.time.LocalDate?, showAverage: Boolean, controls: (@Composable () -> Unit)?,
    onCustomize: (() -> Unit)?, customLabel: String?) {
    if (trend == null) return
    if (trend.points.isEmpty()) {
        MasroofCard(modifier) {
            ChartHeader(title, explanation, onCustomize)
            customLabel?.let { MasroofText(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            controls?.invoke()
            MasroofText(stringResource(R.string.dashboard_chart_no_elapsed_days), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    val date = DateTimeFormatter.ofPattern("d MMM", locale)
    val tick = DateTimeFormatter.ofPattern("d/M", locale)
    var selected by rememberSaveable(trend.points.first().date, trend.points.last().date) { mutableIntStateOf(trend.points.lastIndex) }
    val index = selectedDate?.let { day -> trend.points.indexOfFirst { it.date == day }.takeIf { it >= 0 } }
        ?: selected.coerceIn(trend.points.indices)
    val point = trend.points[index]
    val labels = trend.points.map { "${date.format(it.date)} · ${formatLocalizedMoney(it.spending)}" }
    val currency = MoneyUiFormatter.currencyLabel(point.spending.currency, locale.toLanguageTag())
    // Visual scaling only: the selected amount, tooltip and labels keep the exact values.
    val cappedLabel = remember(trend.points) { com.baraa.masroof.ui.components.MasroofLineChart.cappedOutlierIndex(trend.points.map { it.spending.amount }) }
        ?.let { trend.points[it] }?.let { stringResource(R.string.chart_capped_day_label, formatLocalizedMoney(it.spending), date.format(it.date)) }
    MasroofCard(modifier) {
        ChartHeader(title, explanation, onCustomize)
        customLabel?.let { MasroofText(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("chart-custom-summary")) }
        controls?.let { content -> Column(Modifier.fillMaxWidth().padding(top = MasroofSpacing.sectionGap)) { content() } }
        com.baraa.masroof.ui.dashboard.DashboardTrendHead(date.format(point.date), formatLocalizedMoney(point.spending),
            average = if (showAverage) ({
                MasroofChartAverageLegend(stringResource(R.string.dashboard_chart_average_value, formatLocalizedMoney(trend.averageDailySpending)),
                    Modifier.weight(1f).testTag("daily-expenses-average"))
            }) else null)
        MasroofInteractiveLineChart(trend.points.map { it.spending.amount }, trend.averageDailySpending.amount,
            Modifier.testTag("daily-expenses-chart"), index,
            onPointSelected = { selected = it; onDaySelected?.invoke(trend.points[it].date) },
            pointLabel = { labels[it] }, role = role, axisLabel = { tick.format(trend.points[it].date) },
            currencyLabel = currency, showAverage = showAverage, cappedLabel = cappedLabel?.let { text -> { text } })
        if (cappedLabel != null)
            MasroofText(stringResource(R.string.chart_capped_day_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartHeader(title: String, explanation: String, onCustomize: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
        MasroofGuidanceLabel(title, explanation, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            contentColor = MaterialTheme.colorScheme.onSurface)
        if (onCustomize != null) IconButton(onClick = onCustomize, modifier = Modifier.testTag("chart-customize")) {
            Icon(com.baraa.masroof.ui.dashboard.DashboardChartMenuIcon, androidx.compose.ui.res.stringResource(R.string.chart_customize_title),
                Modifier.size(MasroofReference.icon))
        } else Icon(com.baraa.masroof.presentation.common.MasroofIcons.analysis, null, Modifier.size(MasroofReference.icon))
    }
}
