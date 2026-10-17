package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.ui.theme.MasroofSpacing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.AccountsSummary
import com.baraa.masroof.presentation.common.MasroofCompactCard
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.ui.theme.MasroofThemeExtras

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
    val extended = MasroofThemeExtras.extendedColors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
        if (showExpense) MasroofCompactCard(stringResource(R.string.approved_total_expenses), formatLocalizedMoney(trend.totalSpending),
            resolveMetricToneColor(spendingMetricTone(trend.totalSpending)), MasroofIcons.netSpending, extended.outflowSoft, extended.outflow, modifier = Modifier.weight(1f))
        if (showIncome) MasroofCompactCard(stringResource(R.string.approved_daily_average), formatLocalizedMoney(trend.averageDailySpending),
            MaterialTheme.colorScheme.primary, MasroofIcons.analysis, MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f),
            guidance = stringResource(R.string.approved_average_basis, trend.points.size))
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
        com.baraa.masroof.presentation.common.MasroofShortcutItem(stringResource(R.string.approved_commitments),
            MasroofIcons.calendar, com.baraa.masroof.presentation.common.MasroofCardAccent.Liability, onCommitments),
    ))
}
