package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.ui.theme.MasroofSpacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CurrentAccountFlowDetailGrouping
import com.baraa.masroof.application.dashboard.CurrentAccountSummary
import com.baraa.masroof.application.dashboard.externalMovement
import com.baraa.masroof.application.dashboard.FlowExpenseCategory
import com.baraa.masroof.application.dashboard.FlowIncomeCategory
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
    modifier: Modifier = Modifier,
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
                DashboardFlowDetailMode.Expense -> coreExpenseSummaryRows(summary)
                DashboardFlowDetailMode.Income -> coreIncomeSummaryRows(summary)
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
private fun FlowDetailSummarySection(rows: List<FlowSummaryRow>, direction: TransactionDirectionUi) {
    Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
        DashboardSummaryBreakdownHeader(title = stringResource(R.string.dashboard_flow_detail_breakdown_title))
        rows.forEach { row ->
            FlowDetailSummaryRow(
                label = stringResource(row.labelRes),
                amount = row.amount,
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
        leadingIcon = TransactionDirectionPresentation.icon(direction),
    )
}

private data class FlowSummaryRow(
    val labelRes: Int,
    val amount: Money,
)

private fun coreExpenseSummaryRows(summary: CurrentAccountSummary): List<FlowSummaryRow> =
    listOf(FlowExpenseCategory.POS_PURCHASE, FlowExpenseCategory.CREDIT_CARD_PAYMENT,
        FlowExpenseCategory.LOAN_REPAYMENT, FlowExpenseCategory.BILL_PAYMENT,
        FlowExpenseCategory.EXTERNAL_TRANSFER_OUT, FlowExpenseCategory.CASH_WITHDRAWAL,
        FlowExpenseCategory.FEE).map { category ->
        FlowSummaryRow(
            labelRes = expenseCategoryLabelRes(category),
            amount = expenseAmount(summary, category),
        )
    }

private fun coreIncomeSummaryRows(summary: CurrentAccountSummary): List<FlowSummaryRow> =
    CurrentAccountFlowDetailGrouping.INCOME_DISPLAY_ORDER.map { category ->
        FlowSummaryRow(
            labelRes = incomeCategoryLabelRes(category),
            amount = incomeAmount(summary, category),
        )
    }

private fun expenseAmount(summary: CurrentAccountSummary, category: FlowExpenseCategory): Money =
    when (category) {
        FlowExpenseCategory.EXTERNAL_TRANSFER_OUT -> summary.outflow.externalTransfersOut
        FlowExpenseCategory.CREDIT_CARD_PAYMENT -> summary.outflow.creditCardPayments
        FlowExpenseCategory.CASH_WITHDRAWAL -> summary.outflow.cashWithdrawals
        FlowExpenseCategory.BILL_PAYMENT -> summary.outflow.billPayments
        FlowExpenseCategory.POS_PURCHASE -> summary.outflow.posPurchases
        FlowExpenseCategory.FEE -> summary.outflow.fees
        FlowExpenseCategory.LOAN_REPAYMENT -> summary.outflow.loanRepayments
    }

private fun incomeAmount(summary: CurrentAccountSummary, category: FlowIncomeCategory): Money =
    when (category) {
        FlowIncomeCategory.SALARY -> summary.inflow.salary
        FlowIncomeCategory.EXTERNAL_TRANSFER_IN -> summary.inflow.externalTransfersIn
        FlowIncomeCategory.ACCOUNT_REFUND -> summary.inflow.accountRefunds
        FlowIncomeCategory.OTHER_INCOME -> summary.inflow.otherIncome
    }

private fun expenseCategoryLabelRes(category: FlowExpenseCategory): Int =
    when (category) {
        FlowExpenseCategory.EXTERNAL_TRANSFER_OUT -> R.string.dashboard_external_out_short
        FlowExpenseCategory.CREDIT_CARD_PAYMENT -> R.string.dashboard_credit_card_payment
        FlowExpenseCategory.CASH_WITHDRAWAL -> R.string.dashboard_cash_withdrawals
        FlowExpenseCategory.BILL_PAYMENT -> R.string.dashboard_bill_payments
        FlowExpenseCategory.POS_PURCHASE -> R.string.dashboard_pos_purchases_short
        FlowExpenseCategory.FEE -> R.string.dashboard_fees_short
        FlowExpenseCategory.LOAN_REPAYMENT -> R.string.dashboard_loan_repayments
    }

private fun incomeCategoryLabelRes(category: FlowIncomeCategory): Int =
    when (category) {
        FlowIncomeCategory.SALARY -> R.string.dashboard_salary
        FlowIncomeCategory.EXTERNAL_TRANSFER_IN -> R.string.dashboard_external_in_short
        FlowIncomeCategory.ACCOUNT_REFUND -> R.string.dashboard_account_refunds
        FlowIncomeCategory.OTHER_INCOME -> R.string.dashboard_other_income
    }
