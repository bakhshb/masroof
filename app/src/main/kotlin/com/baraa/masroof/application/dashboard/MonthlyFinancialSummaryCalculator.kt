package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Pure FinancialTransaction → monthly dashboard projection.
 *
 * No Android / Room / Compose dependencies.
 */
object MonthlyFinancialSummaryCalculator {
    fun summarize(
        period: FinancialPeriod,
        transactions: List<FinancialTransaction>,
        reviewRequiredCount: Int,
        primaryCurrency: Currency = Currency.SAR,
        sarEquivalents: Map<String, Money> = emptyMap(),
        parsedRecords: List<ParsedEventRecord> = emptyList(),
    ): MonthlyFinancialSummary {
        val parsedRecordsById = parsedRecords.associateBy { it.event.id }
        val excludedOtherCurrencyCount = transactions.count { tx ->
            tx.amount.currency != primaryCurrency && tx.id !in sarEquivalents
        }

        var spendingGross = Money.zero(primaryCurrency)
        var refunds = Money.zero(primaryCurrency)
        var income = Money.zero(primaryCurrency)
        var externalTransfersIn = Money.zero(primaryCurrency)
        var externalTransfersOut = Money.zero(primaryCurrency)
        var creditCardPayments = Money.zero(primaryCurrency)
        var cashWithdrawals = Money.zero(primaryCurrency)
        var selfTransfers = Money.zero(primaryCurrency)

        for (tx in transactions) {
            val amount = TransactionAmountResolver.effectiveAmount(tx, primaryCurrency, sarEquivalents) ?: continue
            when (tx.type) {
                FinancialTransactionType.EXPENSE,
                FinancialTransactionType.BILL_PAYMENT,
                FinancialTransactionType.FEE,
                -> {
                    // A financing-installment fee is a loan repayment, not spending.
                    val loanFee = tx.type == FinancialTransactionType.FEE &&
                        LoanRepaymentAttribution.isLoanRepayment(tx, parsedRecordsById)
                    if (!loanFee) spendingGross = spendingGross + amount
                }

                FinancialTransactionType.REFUND ->
                    refunds = refunds + amount

                FinancialTransactionType.INCOME ->
                    income = income + amount

                FinancialTransactionType.EXTERNAL_TRANSFER_IN ->
                    externalTransfersIn = externalTransfersIn + amount

                FinancialTransactionType.EXTERNAL_TRANSFER_OUT ->
                    externalTransfersOut = externalTransfersOut + amount

                FinancialTransactionType.CREDIT_CARD_PAYMENT ->
                    creditCardPayments = creditCardPayments + amount

                FinancialTransactionType.LOAN_REPAYMENT ->
                    Unit

                FinancialTransactionType.CASH_WITHDRAWAL ->
                    cashWithdrawals = cashWithdrawals + amount

                FinancialTransactionType.SELF_TRANSFER ->
                    selfTransfers = selfTransfers + amount

                FinancialTransactionType.ADJUSTMENT,
                FinancialTransactionType.UNKNOWN,
                -> Unit
            }
        }

        return MonthlyFinancialSummary(
            period = period,
            currency = primaryCurrency,
            spendingGross = spendingGross,
            refunds = refunds,
            spendingNet = SignedMoneyAmount.difference(spendingGross, refunds),
            income = income,
            externalTransfersIn = externalTransfersIn,
            externalTransfersOut = externalTransfersOut,
            creditCardPayments = creditCardPayments,
            cashWithdrawals = cashWithdrawals,
            selfTransfers = selfTransfers,
            transactionCount = transactions.size,
            reviewRequiredCount = reviewRequiredCount,
            excludedOtherCurrencyCount = excludedOtherCurrencyCount,
        )
    }
}
