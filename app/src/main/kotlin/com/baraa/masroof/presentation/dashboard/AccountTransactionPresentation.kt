package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.dashboard.EntityTransactionScope
import com.baraa.masroof.domain.model.FinancialTransactionType

/**
 * Resolves how a transaction should look on a specific owned-account screen.
 *
 * Global [TransactionTypePresentation.direction] is type-only; self-transfers and
 * paired legs need source/destination context so incoming internal transfers are not
 * shown as spending.
 */
object AccountTransactionPresentation {
    fun directionForAccount(
        tx: TransactionPreviewUi,
        ownedContainerId: String,
        ownedLast4s: Set<String>,
    ): TransactionDirectionUi = directionForAccount(tx, EntityTransactionScope.account(ownedContainerId, ownedLast4s))

    fun directionForAccount(tx: TransactionPreviewUi, account: EntityTransactionScope.Account): TransactionDirectionUi {
        val isSource = EntityTransactionScope.matchesAccount(tx.sourceContainerId, account)
        val isDest = EntityTransactionScope.matchesAccount(tx.destinationContainerId, account)

        return when (tx.type) {
            FinancialTransactionType.SELF_TRANSFER -> when {
                isDest && !isSource -> TransactionDirectionUi.TRANSFER_IN
                isSource && !isDest -> TransactionDirectionUi.OUTWARD
                else -> TransactionDirectionUi.NEUTRAL
            }

            FinancialTransactionType.INCOME ->
                if (isDest) TransactionDirectionUi.INCOME else TransactionTypePresentation.direction(tx.type)

            FinancialTransactionType.EXTERNAL_TRANSFER_IN ->
                if (isDest) TransactionDirectionUi.TRANSFER_IN else TransactionTypePresentation.direction(tx.type)

            FinancialTransactionType.REFUND ->
                if (isDest) TransactionDirectionUi.INWARD else TransactionTypePresentation.direction(tx.type)

            FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            FinancialTransactionType.EXPENSE,
            FinancialTransactionType.CREDIT_CARD_PAYMENT,
            FinancialTransactionType.CASH_WITHDRAWAL,
            FinancialTransactionType.BILL_PAYMENT,
            FinancialTransactionType.LOAN_REPAYMENT,
            FinancialTransactionType.FEE,
            ->
                if (isSource) TransactionDirectionUi.OUTWARD else TransactionTypePresentation.direction(tx.type)

            FinancialTransactionType.ADJUSTMENT,
            FinancialTransactionType.UNKNOWN,
            ->
                TransactionTypePresentation.direction(tx.type)
        }
    }
}
