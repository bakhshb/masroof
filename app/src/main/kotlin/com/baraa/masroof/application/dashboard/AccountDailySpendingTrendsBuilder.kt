package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.parsing.model.isCreditCardSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.math.BigDecimal

internal data class AccountDailySpendingTrends(
    val aggregate: DailySpendingTrend,
    val byAccount: Map<String, DailySpendingTrend>,
)

/** Confirmed cash-account outflows; internal transfers affect their source account only. */
internal object AccountDailySpendingTrendsBuilder {
    fun build(context: DashboardProjectionContext, involvement: Map<String, Set<String>>,
        creditKeys: Set<String>, cardInvolvement: Map<String, Set<String>> =
            CardTransactionInvolvementResolver.buildIndex(context.transactions, context.parsedRecords)): AccountDailySpendingTrends = with(context) {
        val flowContext = AccountFlowClassifier.buildContext(parsedRecords, primaryCurrency, sarEquivalents)
        val aggregateAmounts = linkedMapOf<String, BigDecimal>()
        val allowed = setOf(ChartDataCategory.EXTERNAL_TRANSFERS, ChartDataCategory.CREDIT_SETTLEMENTS,
            ChartDataCategory.CASH_WITHDRAWALS, ChartDataCategory.BILLS, ChartDataCategory.ACCOUNT_PURCHASES,
            ChartDataCategory.FEES, ChartDataCategory.LOAN_REPAYMENTS, ChartDataCategory.UNATTRIBUTED_REFUNDS)
        val aggregateCategories = allowed.associateWith { linkedMapOf<String, BigDecimal>() }
        fun trend(amounts: Map<String, BigDecimal>) = DailySpendingTrendBuilder.buildWithAmounts(
            period, transactions, primaryCurrency, zoneId, today, amounts)
        val perAccount = ownedAccounts.associate { account ->
            val id = FinancialContainerIdFactory.accountId(account.bank, account.maskedNumber)
            val scope = CurrentAccountTransactionScope(setOf(id), AccountFlowScopeMode.SingleAccount, debitCardScope.ownedDebitCardContainerIds,
                debitCardScope.debitCardLinkedAccountIds)
            val amounts = linkedMapOf<String, BigDecimal>()
            val categories = (allowed + ChartDataCategory.INTERNAL_TRANSFERS).associateWith { linkedMapOf<String, BigDecimal>() }
            transactions.distinctBy { it.id }.forEach { transaction ->
                if (transaction.sourceContainerId != id && transaction.destinationContainerId != id &&
                    id !in involvement[transaction.id].orEmpty()) return@forEach
                val amount = TransactionAmountResolver.effectiveAmount(transaction, primaryCurrency, sarEquivalents)?.amount
                    ?: return@forEach
                val assignments = AccountFlowClassifier.classifyWithAmount(transaction, scope, flowContext)
                val expense = assignments.filterIsInstance<FlowAssignment.Expense>().firstOrNull()
                val settlementOrLoan = expense?.category == FlowExpenseCategory.CREDIT_CARD_PAYMENT ||
                    expense?.category == FlowExpenseCategory.LOAN_REPAYMENT
                if (!settlementOrLoan && isCreditCharge(transaction, flowContext.parsedRecordsById,
                        debitCardScope.ownedDebitCardContainerIds, cardInvolvement, creditKeys)) return@forEach
                val contribution = when {
                    expense != null -> amount
                    assignments.any { it is FlowAssignment.Income && it.category == FlowIncomeCategory.ACCOUNT_REFUND } -> amount.negate()
                    assignments.any { it is FlowAssignment.SelfTransfer && it.leg == SelfTransferLeg.OUT } -> amount
                    else -> return@forEach
                }
                amounts[transaction.id] = contribution
                val category = when (expense?.category) {
                    FlowExpenseCategory.EXTERNAL_TRANSFER_OUT -> ChartDataCategory.EXTERNAL_TRANSFERS
                    FlowExpenseCategory.CREDIT_CARD_PAYMENT -> ChartDataCategory.CREDIT_SETTLEMENTS
                    FlowExpenseCategory.CASH_WITHDRAWAL -> ChartDataCategory.CASH_WITHDRAWALS
                    FlowExpenseCategory.BILL_PAYMENT -> ChartDataCategory.BILLS
                    FlowExpenseCategory.POS_PURCHASE -> ChartDataCategory.ACCOUNT_PURCHASES
                    FlowExpenseCategory.FEE -> ChartDataCategory.FEES
                    FlowExpenseCategory.LOAN_REPAYMENT -> ChartDataCategory.LOAN_REPAYMENTS
                    null -> if (transaction.type == FinancialTransactionType.SELF_TRANSFER)
                        ChartDataCategory.INTERNAL_TRANSFERS else ChartDataCategory.UNATTRIBUTED_REFUNDS
                }
                categories.getValue(category)[transaction.id] = contribution
                if (transaction.type != FinancialTransactionType.SELF_TRANSFER) {
                    aggregateAmounts[transaction.id] = contribution
                    aggregateCategories.getValue(category)[transaction.id] = contribution
                }
            }
            "${account.bank.id}:${account.maskedNumber}" to trend(amounts).copy(categoryTrends = categories.mapValues { trend(it.value) })
        }
        AccountDailySpendingTrends(trend(aggregateAmounts).copy(categoryTrends = aggregateCategories.mapValues { trend(it.value) }), perAccount)
    }

    private val creditChargeTypes = setOf(FinancialTransactionType.EXPENSE, FinancialTransactionType.FEE,
        FinancialTransactionType.BILL_PAYMENT, FinancialTransactionType.CASH_WITHDRAWAL, FinancialTransactionType.REFUND)

    private fun isCreditCharge(transaction: FinancialTransaction, parsedById: Map<String, ParsedEventRecord>, debitContainers: Set<String>,
        involvement: Map<String, Set<String>>, creditKeys: Set<String>): Boolean {
        if (transaction.type !in creditChargeTypes) return false
        if (transaction.linkedParsedEventIds.any { parsedById[it]?.details?.isCreditCardSms() == true }) return true
        if (involvement[transaction.id].orEmpty().any { it in creditKeys }) return true
        val card = if (transaction.type == FinancialTransactionType.REFUND) transaction.destinationContainerId else transaction.sourceContainerId
        return com.baraa.masroof.domain.ids.FinancialContainerIdParser.isCardContainer(card) && card !in debitContainers
    }
}
