package com.baraa.masroof.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CurrentAccountSummary
import com.baraa.masroof.application.dashboard.DailySpendingTrend
import com.baraa.masroof.application.dashboard.cashPosition
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.ui.theme.*

@Composable
fun AccountPeriodSummaryCard(summary: CurrentAccountSummary, modifier: Modifier = Modifier,
    title: String = stringResource(R.string.dashboard_remaining_title), subtitle: String? = null,
    trend: DailySpendingTrend? = null, chartKey: String = "account") {
    val colors = MasroofThemeExtras.extendedColors
    val movement = summary.cashPosition()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        MasroofHeroCard(title, formatLocalizedMoney(movement.remaining), subtitle,
            role = MasroofHeroRole.Account, guidance = stringResource(R.string.dashboard_account_remaining_calculated_hint))
        com.baraa.masroof.presentation.common.MasroofMetricRow { tile ->
            MasroofMetric(stringResource(R.string.dashboard_total_inflow), formatLocalizedMoney(movement.inflow),
                colors.inflow, tile)
            MasroofMetric(stringResource(R.string.dashboard_total_spent), formatLocalizedMoney(movement.outflow),
                colors.outflow, tile)
        }
        SpendingTrendSection(trend, stringResource(R.string.account_daily),
            explanation = stringResource(R.string.reference_account_detail_scope), role = MasroofHeroRole.Account,
            chartKey = chartKey, scopeLabel = title)
        AccountReferenceGroup(R.string.reference_inflow_external, accountInflowRows(summary), colors.inflow)
        AccountReferenceGroup(R.string.reference_outflow_external, accountOutflowRows(summary), colors.outflow)
        val transfers = listOf(R.string.dashboard_self_transfer_in to summary.inflow.selfTransfersIn,
            R.string.dashboard_self_transfer_out to summary.outflow.selfTransfersOut)
        AccountReferenceGroup(R.string.dashboard_self_transfers, transfers, colors.account,
            stringResource(R.string.dashboard_account_remaining_calculated_hint))
    }
}

/** External inflow rows (label, amount) in display order; shared with the flow detail screen. */
internal fun accountInflowRows(summary: CurrentAccountSummary): List<Pair<Int, Money>> = listOf(
    R.string.dashboard_salary to summary.inflow.salary,
    R.string.dashboard_external_in_short to summary.inflow.externalTransfersIn,
    R.string.dashboard_account_refunds to summary.inflow.accountRefunds,
    R.string.dashboard_other_income to summary.inflow.otherIncome,
)

/** External outflow rows (label, amount) in display order; shared with the flow detail screen. */
internal fun accountOutflowRows(summary: CurrentAccountSummary): List<Pair<Int, Money>> = listOf(
    R.string.dashboard_pos_purchases_short to summary.outflow.posPurchases,
    R.string.dashboard_credit_card_payment to summary.outflow.creditCardPayments,
    R.string.dashboard_loan_repayments to summary.outflow.loanRepayments,
    R.string.dashboard_bill_payments to summary.outflow.billPayments,
    R.string.dashboard_external_out_short to summary.outflow.externalTransfersOut,
    R.string.dashboard_cash_withdrawals to summary.outflow.cashWithdrawals,
    R.string.dashboard_fees_short to summary.outflow.fees,
)

@Composable
private fun AccountReferenceGroup(title: Int, rows: List<Pair<Int, Money>>, color: androidx.compose.ui.graphics.Color,
    guidance: String? = null) {
    val relevant = rows.filter { it.second.amount.signum() != 0 }
    if (relevant.isEmpty()) return
    MasroofCard {
        MasroofGuidanceLabel(stringResource(title), guidance, style = MaterialTheme.typography.titleMedium,
            contentColor = MaterialTheme.colorScheme.onSurface)
        relevant.forEach { (label, value) ->
            MasroofDetailRow(stringResource(label), formatLocalizedMoney(value), valueColor = color)
        }
    }
}
