package com.baraa.masroof.presentation.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.*
import com.baraa.masroof.presentation.common.MasroofBottomSheet
import com.baraa.masroof.ui.theme.*

val LocalChartDataWorkflow = staticCompositionLocalOf {
    ChartDataWorkflow(InMemoryChartDataPreferencesRepository())
}

@Composable
internal fun selectedChartTrend(trend: DailySpendingTrend, key: String): DailySpendingTrend {
    val workflow = LocalChartDataWorkflow.current
    val choices by workflow.preferences.collectAsState()
    return remember(trend, key, choices) { workflow.selected(trend, key) }
}

@Composable
internal fun chartCategoryLabel(category: ChartDataCategory): String = stringResource(when (category) {
    ChartDataCategory.CREDIT_PURCHASES -> R.string.chart_category_credit
    ChartDataCategory.MADA_PURCHASES -> R.string.chart_category_mada
    ChartDataCategory.ACCOUNT_PURCHASES -> R.string.chart_category_account_purchases
    ChartDataCategory.OTHER_EXPENSES -> R.string.chart_category_other
    ChartDataCategory.CASH_WITHDRAWALS -> R.string.chart_category_cash
    ChartDataCategory.EXTERNAL_TRANSFERS -> R.string.chart_category_external
    ChartDataCategory.BILLS -> R.string.chart_category_bills
    ChartDataCategory.LOAN_REPAYMENTS -> R.string.chart_category_loans
    ChartDataCategory.CREDIT_SETTLEMENTS -> R.string.chart_category_settlements
    ChartDataCategory.FEES -> R.string.chart_category_fees
    ChartDataCategory.INTERNAL_TRANSFERS -> R.string.chart_category_internal
    ChartDataCategory.UNATTRIBUTED_REFUNDS -> R.string.chart_category_refunds
})

@Composable
internal fun ChartDataBottomSheet(trend: DailySpendingTrend, chartKey: String, title: String, onDismiss: () -> Unit) {
    val workflow = LocalChartDataWorkflow.current
    var draft by rememberSaveable(chartKey) { mutableStateOf(workflow.categories(trend, chartKey).map { it.name }) }
    val selected = draft.mapNotNull { runCatching { ChartDataCategory.valueOf(it) }.getOrNull() }.toSet()
    MasroofBottomSheet(stringResource(R.string.chart_customize_title), onDismiss,
        onApply = { workflow.save(chartKey, selected, trend.categoryTrends.keys); onDismiss() },
        applyLabel = stringResource(R.string.chart_save_apply), subtitle = title, canApply = selected.isNotEmpty(),
        resetLabel = stringResource(R.string.chart_restore_defaults), onReset = { draft = trend.categoryTrends.keys.map { it.name } }) {
        com.baraa.masroof.presentation.common.MasroofSheetChoices(trend.categoryTrends.keys.toList(), selected,
            { chartCategoryLabel(it) }, { category ->
                draft = if (category.name in draft) draft - category.name else draft + category.name
            }, { "chart-category-${it.name}" })
        com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(R.string.chart_data_about),
            stringResource(R.string.chart_refunds_selection_hint))
    }
}
