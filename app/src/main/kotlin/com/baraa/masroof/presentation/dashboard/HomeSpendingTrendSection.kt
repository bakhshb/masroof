package com.baraa.masroof.presentation.dashboard

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.DashboardSpendingTrends
import com.baraa.masroof.presentation.common.MasroofChartScopeTabs
import com.baraa.masroof.presentation.common.MasroofText
import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier

/** One Home chart. The selected scope owns its series, average and contextual guidance. */
@Composable
fun HomeSpendingTrendSection(trends: DashboardSpendingTrends) {
    val workflow = LocalChartDataWorkflow.current
    val preferences by workflow.preferences.collectAsState()
    val selected = if (preferences.homeScope == "overall") 1 else 0
    val labels = listOf(stringResource(R.string.dashboard_daily_purchases), stringResource(R.string.all_expenses))
    SpendingTrendSection(
        trend = if (selected == 0) trends.purchases else trends.overall,
        title = stringResource(R.string.home_daily_spending_title),
        explanation = stringResource(if (selected == 0) R.string.dashboard_purchases_trend_basis else R.string.dashboard_all_trend_basis),
        showAverage = true,
        chartKey = if (selected == 0) "home:purchases" else "home:overall",
        scopeLabel = labels[selected],
        controls = {
            MasroofChartScopeTabs(labels, selected, onSelected = { workflow.setHomeScope(if (it == 0) "purchases" else "overall") })
            if (selected == 0) MasroofText(stringResource(R.string.home_daily_spending_purchases_scope),
                Modifier.padding(top = MasroofSpacing.inlineGap), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
