package com.baraa.masroof.domain.model

import com.baraa.masroof.core.money.Money
import java.time.Instant

/** The explicit decisions a user makes; each is one append to `user_decision` (docs/ARCHITECTURE.md). */
enum class UserDecisionKind {
    FINANCIAL_TYPE,
    EXTERNAL_TRANSFER,
    SELF_TRANSFER_PAIR,
    NON_FINANCIAL,
    CORRECTION,
    RECLASSIFY,
    IGNORE,
    RESTORE,
    RESTORE_REVERTED,
    BANK_SELECTION,
}

/**
 * One explicit user decision about [rawSmsId] (and [pairedRawSmsId] for a self-transfer pair).
 * Only the fields of its [kind] are set; the database assigns the order.
 */
data class UserDecision(
    val rawSmsId: String,
    val kind: UserDecisionKind,
    val decidedAt: Instant,
    val pairedRawSmsId: String? = null,
    val transactionType: FinancialTransactionType? = null,
    val sourceContainerId: String? = null,
    val destinationContainerId: String? = null,
    val messageFamily: MessageFamily? = null,
    val amount: Money? = null,
    val merchant: String? = null,
    val counterparty: String? = null,
    val bankId: String? = null,
) {
    companion object {
        /** A posting decision: the type and endpoints of [transaction]. */
        fun posting(
            kind: UserDecisionKind,
            rawSmsId: String,
            transaction: FinancialTransaction,
            decidedAt: Instant,
            pairedRawSmsId: String? = null,
        ) = UserDecision(
            rawSmsId = rawSmsId,
            kind = kind,
            decidedAt = decidedAt,
            pairedRawSmsId = pairedRawSmsId,
            transactionType = transaction.type,
            sourceContainerId = transaction.sourceContainerId,
            destinationContainerId = transaction.destinationContainerId,
        )
    }
}
