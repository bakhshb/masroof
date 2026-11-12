package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/** The approved Home scope uses persisted card attribution, never an account-wide guess. */
internal object DailyPurchasesScope {
    fun includes(
        transaction: FinancialTransaction,
        attributedToCard: Boolean,
        parsedById: Map<String, ParsedEventRecord>,
    ): Boolean {
        if (LoanRepaymentAttribution.isLoanRepayment(transaction, parsedById)) return false
        val families = transaction.linkedParsedEventIds.mapNotNull { parsedById[it]?.event?.messageFamily }.toSet()
        if (families.any { it == MessageFamily.CARD_PAYMENT || it == MessageFamily.BILL_PAYMENT || it == MessageFamily.FINANCING_INSTALLMENT }) return false
        return when (transaction.type) {
            FinancialTransactionType.CASH_WITHDRAWAL,
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            -> true
            FinancialTransactionType.EXPENSE ->
                MessageFamily.WITHDRAWAL in families || (attributedToCard && MessageFamily.FEE !in families)
            FinancialTransactionType.REFUND -> attributedToCard
            // Some banks persist an ATM charge as FEE with a WITHDRAWAL family.
            FinancialTransactionType.FEE -> MessageFamily.WITHDRAWAL in families
            else -> false
        }
    }
}
