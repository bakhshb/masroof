package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.parsing.model.isDebitCardSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Shared rules for Mada salary-period spending: POS purchases, cash withdrawals, and refunds.
 */
object DebitCardSpendClassifier {
    enum class Effect {
        Expense,
        Refund,
    }

    fun scopeFor(
        entry: CardRegistryEntry,
        ownedAccountContainerIds: Set<String>,
        ownedAccountLast4s: Set<String>,
    ): CurrentAccountTransactionScope {
        val linkedContainerId = entry.linkedAccount?.let { account ->
            FinancialContainerIdFactory.accountId(account)
        }
        val linkedLast4s = entry.linkedAccountMaskedNumber?.let { masked ->
            CurrentAccountTransactionScope.ownedAccountLast4sFromMaskedNumbers(listOf(masked))
        }.orEmpty()
        return when {
            linkedContainerId != null ->
                CurrentAccountTransactionScope(
                    ownedContainerIds = setOf(linkedContainerId),
                    ownedAccountLast4s = linkedLast4s,
                    mode = AccountFlowScopeMode.SingleAccount,
                )
            else ->
                CurrentAccountTransactionScope(
                    ownedContainerIds = ownedAccountContainerIds,
                    ownedAccountLast4s = ownedAccountLast4s,
                    mode = AccountFlowScopeMode.Fleet,
                )
        }
    }

    fun effectFor(
        tx: FinancialTransaction,
        bankId: String,
        last4: String,
        scope: CurrentAccountTransactionScope,
        context: AccountFlowClassificationContext,
        cardInvolvement: Map<String, Set<String>>,
    ): Effect? {
        if (!CardTransactionInvolvementResolver.matchesCard(tx.id, bankId, last4, cardInvolvement)) {
            return null
        }
        if (tx.type == FinancialTransactionType.REFUND) {
            return Effect.Refund
        }
        return if (classifiesAsDebitSpend(tx, scope, context)) Effect.Expense else null
    }

    private fun classifiesAsDebitSpend(
        tx: FinancialTransaction,
        scope: CurrentAccountTransactionScope,
        context: AccountFlowClassificationContext,
    ): Boolean {
        val fromAccountFlow = AccountFlowClassifier.classify(tx, scope, context).any { assignment ->
            assignment is FlowAssignment.Expense &&
                (
                    assignment.category == FlowExpenseCategory.POS_PURCHASE ||
                        assignment.category == FlowExpenseCategory.CASH_WITHDRAWAL
                    )
        }
        if (fromAccountFlow) return true
        // Google Pay / wallet Mada POS often has no source account; the source container is the card.
        return isCardAttributedDebitSpend(tx, context)
    }

    private fun isCardAttributedDebitSpend(
        tx: FinancialTransaction,
        context: AccountFlowClassificationContext,
    ): Boolean {
        if (tx.type != FinancialTransactionType.EXPENSE &&
            tx.type != FinancialTransactionType.CASH_WITHDRAWAL
        ) {
            return false
        }
        if (isExcludedDebitSpend(tx, context)) return false
        return linkedRecords(tx, context).any { record -> isDebitSpendRecord(record, context) }
    }

    private fun isDebitSpendRecord(
        record: ParsedEventRecord,
        context: AccountFlowClassificationContext,
    ): Boolean {
        if (!record.details.isDebitCardSms()) return false
        return when (record.event.messageFamily) {
            MessageFamily.PURCHASE,
            MessageFamily.WITHDRAWAL,
            -> true
            else -> false
        }
    }

    private fun isExcludedDebitSpend(
        tx: FinancialTransaction,
        context: AccountFlowClassificationContext,
    ): Boolean {
        if (tx.id in context.billPaymentTxIds) return true
        if (tx.type == FinancialTransactionType.BILL_PAYMENT ||
            tx.type == FinancialTransactionType.CREDIT_CARD_PAYMENT
        ) {
            return true
        }
        return linkedRecords(tx, context).any { record ->
            record.event.messageFamily == MessageFamily.BILL_PAYMENT ||
                record.event.messageFamily == MessageFamily.CARD_PAYMENT
        }
    }

    private fun linkedRecords(
        tx: FinancialTransaction,
        context: AccountFlowClassificationContext,
    ): List<ParsedEventRecord> =
        tx.linkedParsedEventIds.mapNotNull { context.parsedRecordsById[it] }
}
