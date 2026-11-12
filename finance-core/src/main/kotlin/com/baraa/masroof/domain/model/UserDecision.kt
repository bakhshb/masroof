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
    val occurredAt: Instant? = null,
    val occurredAtZone: String? = null,
    val messageFamily: MessageFamily? = null,
    val amount: Money? = null,
    val merchant: String? = null,
    val counterparty: String? = null,
    val bankId: String? = null,
) {
    companion object {
        /** A posting decision: the type, endpoints and effective time of [transaction], as posted. */
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
            occurredAt = transaction.occurredAt,
            occurredAtZone = transaction.occurredAtZone,
        )
    }
}

/**
 * The decisions in force for one raw SMS, folded from its `user_decision` rows in `sequence` order
 * (docs/ARCHITECTURE.md, "Reading decisions"). Three independent layers:
 * - [outcome]: the latest row of an outcome kind naming the SMS as subject or paired leg; outcomes supersede
 *   one another.
 * - [corrections]: every `CORRECTION` of the SMS, oldest `decidedAt` first (ties in `sequence` order).
 * - [selectedBankId]: the bank of the latest `BANK_SELECTION`.
 */
data class EffectiveUserDecisions(
    val outcome: UserDecision? = null,
    val corrections: List<UserDecision> = emptyList(),
    val selectedBankId: String? = null,
) {
    /** "Post nothing for this SMS": what a resolved `USER_NON_FINANCIAL` review meant. */
    val isNonFinancial: Boolean get() = outcome?.kind in NON_FINANCIAL_OUTCOMES

    /** "This SMS is financial": what a `USER_FINANCIAL_TYPE` review meant (chosen type, reclassify or restore). */
    val confirmsFinancial: Boolean get() = outcome?.kind in FINANCIAL_OUTCOMES

    companion object {
        val NONE = EffectiveUserDecisions()

        private val NON_FINANCIAL_OUTCOMES =
            setOf(UserDecisionKind.NON_FINANCIAL, UserDecisionKind.IGNORE, UserDecisionKind.RESTORE_REVERTED)
        private val FINANCIAL_OUTCOMES =
            setOf(UserDecisionKind.FINANCIAL_TYPE, UserDecisionKind.RECLASSIFY, UserDecisionKind.RESTORE)

        /** Folds [decisions], the rows naming [rawSmsId] in `sequence` order. */
        fun of(rawSmsId: String, decisions: List<UserDecision>): EffectiveUserDecisions {
            val own = decisions.filter { it.rawSmsId == rawSmsId }
            return EffectiveUserDecisions(
                outcome = decisions.lastOrNull {
                    it.kind != UserDecisionKind.CORRECTION && it.kind != UserDecisionKind.BANK_SELECTION &&
                        (it.rawSmsId == rawSmsId || it.pairedRawSmsId == rawSmsId)
                },
                corrections = own.filter { it.kind == UserDecisionKind.CORRECTION }.sortedBy { it.decidedAt },
                selectedBankId = own.lastOrNull { it.kind == UserDecisionKind.BANK_SELECTION }?.bankId,
            )
        }
    }
}
