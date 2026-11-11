package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.ui.theme.MasroofSpacing

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.AccountsSummary
import com.baraa.masroof.presentation.common.MasroofMetric
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.locale.formatLocalizedMoney

@Composable
fun DashboardHeroCard(
    accountsFleet: AccountsSummary,
    onIncome: () -> Unit, onOutflow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unavailable = stringResource(R.string.dashboard_value_unavailable)
    val state = ProductionDashboardVisualMapper.hero(
        stringResource(R.string.dashboard_hero_remaining_title),
        accountsFleet.totalRemaining?.let { formatLocalizedMoney(it) } ?: unavailable,
        stringResource(R.string.reference_inflow_short),
        accountsFleet.totalInflow?.let { formatLocalizedMoney(it) } ?: unavailable,
        stringResource(R.string.reference_outflow_short),
        accountsFleet.totalOutflow?.let { formatLocalizedMoney(it) } ?: unavailable)
    com.baraa.masroof.ui.dashboard.DashboardHomeHero(state, MasroofIcons.appLogo, onIncome, onOutflow, modifier)
}

@Composable
fun DashboardQuickSummaryRow(
    trend: com.baraa.masroof.application.dashboard.DailySpendingTrend?,
    showExpense: Boolean = true, showIncome: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (trend == null) return
    com.baraa.masroof.presentation.common.MasroofMetricRow(modifier, MasroofSpacing.cardInnerGap) { tile ->
        if (showExpense) MasroofMetric(stringResource(R.string.total_expenses), formatLocalizedMoney(trend.totalSpending),
            resolveMetricToneColor(spendingMetricTone(trend.totalSpending)), modifier = tile)
        if (showIncome) MasroofMetric(stringResource(R.string.home_avg_daily_all_expenses), formatLocalizedMoney(trend.averageDailySpending),
            MaterialTheme.colorScheme.primary, modifier = tile,
            guidance = stringResource(R.string.average_basis, trend.points.size))
    }
}

@Composable
fun DashboardShortcuts(onAccounts: () -> Unit, onCards: () -> Unit, onLoans: () -> Unit, onCommitments: () -> Unit) {
    com.baraa.masroof.presentation.common.MasroofShortcutStrip(listOf(
        com.baraa.masroof.presentation.common.MasroofShortcutItem(stringResource(R.string.reference_shortcut_accounts),
            MasroofIcons.moneyMovement, com.baraa.masroof.presentation.common.MasroofCardAccent.Account, onAccounts),
        com.baraa.masroof.presentation.common.MasroofShortcutItem(stringResource(R.string.reference_shortcut_cards),
            MasroofIcons.cardPayment, com.baraa.masroof.presentation.common.MasroofCardAccent.Credit, onCards),
        com.baraa.masroof.presentation.common.MasroofShortcutItem(stringResource(R.string.reference_shortcut_loans),
            MasroofIcons.savings, com.baraa.masroof.presentation.common.MasroofCardAccent.Liability, onLoans),
        com.baraa.masroof.presentation.common.MasroofShortcutItem(stringResource(R.string.commitments),
            MasroofIcons.calendar, com.baraa.masroof.presentation.common.MasroofCardAccent.Liability, onCommitments),
    ))
}
