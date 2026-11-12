package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import java.math.BigDecimal

/** Uses persisted facts and existing contribution policies, never SMS text. */
internal object ChartCategoryTrendsBuilder {
    val purchases = setOf(ChartDataCategory.CREDIT_PURCHASES, ChartDataCategory.MADA_PURCHASES,
        ChartDataCategory.CASH_WITHDRAWALS, ChartDataCategory.EXTERNAL_TRANSFERS, ChartDataCategory.UNATTRIBUTED_REFUNDS)
    val credit = setOf(ChartDataCategory.CREDIT_PURCHASES, ChartDataCategory.BILLS,
        ChartDataCategory.FEES, ChartDataCategory.CASH_WITHDRAWALS, ChartDataCategory.UNATTRIBUTED_REFUNDS)
    val debit = setOf(ChartDataCategory.MADA_PURCHASES, ChartDataCategory.CASH_WITHDRAWALS,
        ChartDataCategory.BILLS, ChartDataCategory.FEES, ChartDataCategory.UNATTRIBUTED_REFUNDS)
    val overall = purchases + setOf(ChartDataCategory.OTHER_EXPENSES, ChartDataCategory.BILLS,
        ChartDataCategory.FEES, ChartDataCategory.LOAN_REPAYMENTS, ChartDataCategory.UNATTRIBUTED_REFUNDS)

    fun build(context: DashboardProjectionContext, rows: List<FinancialTransaction>, outflows: Boolean,
        allowed: Set<ChartDataCategory>, creditIds: Set<String>, debitIds: Set<String>): Map<ChartDataCategory, DailySpendingTrend> = with(context) {
        val parsedById = parsedRecords.associateBy { it.event.id }
        val amounts = allowed.associateWith { linkedMapOf<String, BigDecimal>() }
        rows.distinctBy { it.id }.forEach { row ->
            val amount = DailySpendingTrendBuilder.signedSpendingAmount(row, primaryCurrency, sarEquivalents, parsedById, outflows)
                ?: return@forEach
            val families = row.linkedParsedEventIds.mapNotNull { parsedById[it]?.event?.messageFamily }.toSet()
            val category = when {
                row.type == FinancialTransactionType.REFUND -> ChartDataCategory.UNATTRIBUTED_REFUNDS
                LoanRepaymentAttribution.isLoanRepayment(row, parsedById) -> ChartDataCategory.LOAN_REPAYMENTS
                row.type == FinancialTransactionType.CASH_WITHDRAWAL || MessageFamily.WITHDRAWAL in families -> ChartDataCategory.CASH_WITHDRAWALS
                row.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT -> ChartDataCategory.EXTERNAL_TRANSFERS
                row.type == FinancialTransactionType.BILL_PAYMENT -> ChartDataCategory.BILLS
                row.type == FinancialTransactionType.FEE -> ChartDataCategory.FEES
                row.id in creditIds -> ChartDataCategory.CREDIT_PURCHASES
                row.id in debitIds -> ChartDataCategory.MADA_PURCHASES
                else -> ChartDataCategory.OTHER_EXPENSES
            }
            amounts[category]?.put(row.id, amount)
        }
        amounts.mapValues { (_, values) -> DailySpendingTrendBuilder.buildWithAmounts(period, rows,
            primaryCurrency, zoneId, today, values) }
    }
}
