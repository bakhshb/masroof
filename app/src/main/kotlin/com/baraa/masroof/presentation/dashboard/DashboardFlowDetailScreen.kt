package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.ui.theme.MasroofSpacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CurrentAccountFlowDetailGrouping
import com.baraa.masroof.application.dashboard.CurrentAccountSummary
import com.baraa.masroof.application.dashboard.externalMovement
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofCardAccent
import com.baraa.masroof.presentation.common.MasroofMoneyRow
import com.baraa.masroof.presentation.common.MasroofMoneyRowStyle
import com.baraa.masroof.presentation.locale.formatLocalizedMoney

enum class DashboardFlowDetailMode {
    Expense,
    Income,
}

@Composable
fun DashboardFlowDetailScreen(
    mode: DashboardFlowDetailMode,
    summary: CurrentAccountSummary,
    state: DashboardUiState,
    transactions: List<TransactionPreviewUi>,
    grouping: CurrentAccountFlowDetailGrouping,
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit,
) {
    val movement = summary.externalMovement()
    val presentation = when (mode) {
        DashboardFlowDetailMode.Expense -> FlowDetailPresentation(
            titleRes = R.string.dashboard_expense_details_title,
            total = movement.outflow,
            totalTitleRes = R.string.dashboard_total_spent,
            direction = TransactionDirectionUi.OUTWARD,
            totalHintRes = R.string.dashboard_flow_detail_expense_total_hint,
        )
        DashboardFlowDetailMode.Income -> FlowDetailPresentation(
            titleRes = R.string.dashboard_income_details_title,
            total = movement.inflow,
            totalTitleRes = R.string.dashboard_total_inflow,
            direction = TransactionDirectionUi.INCOME,
            totalHintRes = R.string.dashboard_flow_detail_income_total_hint,
        )
    }
    val formattedTotal = formatLocalizedMoney(presentation.total)
    val previewsById = remember(transactions) { transactions.associateBy { it.id } }

    DashboardSummaryScaffold(
        title = stringResource(presentation.titleRes),
        state = state,
        onBack = onBack,
    ) {
        com.baraa.masroof.presentation.common.MasroofHeroCard(
            stringResource(presentation.totalTitleRes), formattedTotal,
            guidance = stringResource(presentation.totalHintRes),
            role = if (mode == DashboardFlowDetailMode.Income) com.baraa.masroof.ui.theme.MasroofHeroRole.Inflow else com.baraa.masroof.ui.theme.MasroofHeroRole.Outflow)
        MasroofCard {
            val rows = when (mode) {
                DashboardFlowDetailMode.Expense -> accountOutflowRows(summary)
                DashboardFlowDetailMode.Income -> accountInflowRows(summary)
            }
            FlowDetailSummarySection(rows, presentation.direction)
        }

        val flowTransactions = when (mode) {
            DashboardFlowDetailMode.Expense -> grouping.expense.values
            DashboardFlowDetailMode.Income -> grouping.income.values
        }.flatten().mapNotNull { previewsById[it.id] }.sortedWith(
            compareByDescending<TransactionPreviewUi> { it.localDate }.thenByDescending { it.id },
        )
        if (flowTransactions.isNotEmpty()) {
            DashboardSummaryTransactionsSection(
                transactions = flowTransactions,
                onOpenTransaction = onOpenTransaction,
                ownedCards = state.ownedCards,
            )
        }
    }
}

private data class FlowDetailPresentation(
    val titleRes: Int,
    val total: Money,
    val totalTitleRes: Int,
    val direction: TransactionDirectionUi,
    val totalHintRes: Int,
)

@Composable
private fun FlowDetailSummarySection(rows: List<Pair<Int, Money>>, direction: TransactionDirectionUi) {
    Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
        DashboardSummaryBreakdownHeader(title = stringResource(R.string.dashboard_flow_detail_breakdown_title))
        rows.forEach { (labelRes, amount) ->
            FlowDetailSummaryRow(
                label = stringResource(labelRes),
                amount = amount,
                direction = direction,
            )
        }
    }
}

@Composable
private fun FlowDetailSummaryRow(
    label: String,
    amount: Money,
    direction: TransactionDirectionUi,
) {
    if (amount.amount.signum() == 0) return
    val style = when {
        direction == TransactionDirectionUi.OUTWARD -> MasroofMoneyRowStyle.Outflow
        direction == TransactionDirectionUi.NEUTRAL -> MasroofMoneyRowStyle.Neutral
        else -> MasroofMoneyRowStyle.Inflow
    }
    MasroofMoneyRow(
        label = label,
        value = formatLocalizedMoney(amount),
        style = style,
    )
}
