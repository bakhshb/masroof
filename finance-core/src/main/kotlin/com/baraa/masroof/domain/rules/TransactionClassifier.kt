package com.baraa.masroof.domain.rules

import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.TransferOwnershipType

/**
 * Pure domain classifier: structured facts + resolved ownership → financial meaning.
 *
 * Does not parse SMS, match related events, or consult bank-specific wording.
 * [ClassificationEvidence.bankNetworkType] is ignored for ownership decisions.
 */
object TransactionClassifier {

    fun classify(evidence: ClassificationEvidence): ClassificationResult {
        return when (evidence.messageFamily) {
            MessageFamily.OTP,
            MessageFamily.BALANCE_NOTICE,
            MessageFamily.NON_FINANCIAL,
            ->
                ClassificationResult.NeedsReview(
                    tentativeType = null,
                    transferOwnership = null,
                    impact = FinancialImpactCalculator.unresolved(),
                    reasons = listOf("non_financial_or_informational_message"),
                )

            MessageFamily.UNKNOWN ->
                ClassificationResult.NeedsReview(
                    tentativeType = FinancialTransactionType.UNKNOWN,
                    transferOwnership = null,
                    impact = FinancialImpactCalculator.unresolved(),
                    reasons = listOf("unknown_message_family"),
                )

            MessageFamily.REFUND ->
                classified(
                    type = FinancialTransactionType.REFUND,
                    transferOwnership = null,
                    reasons = listOf("message_family_refund"),
                )

            MessageFamily.WITHDRAWAL ->
                classified(
                    type = FinancialTransactionType.CASH_WITHDRAWAL,
                    transferOwnership = null,
                    reasons = listOf("message_family_withdrawal"),
                )

            MessageFamily.FEE ->
                classified(
                    type = FinancialTransactionType.FEE,
                    transferOwnership = null,
                    reasons = listOf("message_family_fee"),
                )

            MessageFamily.BILL_PAYMENT ->
                classified(
                    type = FinancialTransactionType.BILL_PAYMENT,
                    transferOwnership = null,
                    reasons = listOf("message_family_bill_payment"),
                )

            MessageFamily.FINANCING_INSTALLMENT ->
                classifyLoanRepayment(evidence)

            MessageFamily.PURCHASE ->
                classifyPurchase(evidence)

            MessageFamily.CARD_PAYMENT ->
                classifyCardPayment(evidence)

            MessageFamily.TRANSFER_IN,
            MessageFamily.TRANSFER_OUT,
            ->
                classifyTransfer(evidence)
        }
    }

    private fun classifyPurchase(evidence: ClassificationEvidence): ClassificationResult {
        val instrument = evidence.instrument
        if (instrument != null && instrument.ownership == OwnershipStatus.UNKNOWN) {
            return needsReview(
                tentativeType = FinancialTransactionType.EXPENSE,
                transferOwnership = null,
                reasons = listOf("purchase_instrument_ownership_unknown"),
            )
        }
        if (instrument != null && instrument.ownership == OwnershipStatus.EXTERNAL) {
            return needsReview(
                tentativeType = null,
                transferOwnership = null,
                reasons = listOf("purchase_on_external_instrument"),
            )
        }

        // Owned account/card funding reference is sufficient for expense
        // without inventing debit/credit type; an owned credit card is an expense at purchase time (D-006).
        if (instrument != null && instrument.ownership == OwnershipStatus.OWNED) {
            return classified(
                type = FinancialTransactionType.EXPENSE,
                transferOwnership = null,
                reasons = listOf(
                    "owned_instrument_purchase",
                    evidence.purchaseChannel?.let { "channel_$it" } ?: "channel_unspecified",
                ),
            )
        }

        // Purchase family without a resolved owned instrument — still expense by family,
        // but flag for review when ownership of the funding instrument is missing.
        return needsReview(
            tentativeType = FinancialTransactionType.EXPENSE,
            transferOwnership = null,
            reasons = listOf("purchase_without_resolved_owned_instrument"),
        )
    }

    private fun classifyLoanRepayment(evidence: ClassificationEvidence): ClassificationResult {
        val source = evidence.source
        val destination = evidence.destination
        val loanType = evidence.loanType

        if (source == null || destination == null || loanType == null) {
            return needsReview(
                tentativeType = FinancialTransactionType.LOAN_REPAYMENT,
                transferOwnership = null,
                reasons = listOf("loan_repayment_missing_containers_or_type"),
            )
        }

        if (source.kind != ContainerKind.ACCOUNT || destination.kind != ContainerKind.LOAN) {
            return needsReview(
                tentativeType = FinancialTransactionType.LOAN_REPAYMENT,
                transferOwnership = null,
                reasons = listOf("loan_repayment_invalid_container_shape"),
            )
        }

        val ownership = TransferOwnershipResolver.resolve(source.ownership, destination.ownership)
        if (ownership == TransferOwnershipType.UNKNOWN) {
            return needsReview(
                tentativeType = FinancialTransactionType.LOAN_REPAYMENT,
                transferOwnership = ownership,
                reasons = listOf("loan_repayment_ownership_unresolved"),
            )
        }

        if (source.ownership == OwnershipStatus.OWNED && destination.ownership == OwnershipStatus.OWNED) {
            return classified(
                type = FinancialTransactionType.LOAN_REPAYMENT,
                transferOwnership = ownership,
                reasons = listOf(
                    "owned_account_to_owned_loan",
                    "not_a_new_expense",
                    "loan_type_$loanType",
                ),
            )
        }

        return needsReview(
            tentativeType = FinancialTransactionType.LOAN_REPAYMENT,
            transferOwnership = ownership,
            reasons = listOf("loan_repayment_family_without_owned_account_to_loan_shape"),
        )
    }

    private fun classifyCardPayment(evidence: ClassificationEvidence): ClassificationResult {
        val source = evidence.source
        val destination = evidence.destination

        if (source == null || destination == null) {
            return needsReview(
                tentativeType = FinancialTransactionType.CREDIT_CARD_PAYMENT,
                transferOwnership = null,
                reasons = listOf("card_payment_missing_containers"),
            )
        }

        val ownership = TransferOwnershipResolver.resolve(source.ownership, destination.ownership)
        if (ownership == TransferOwnershipType.UNKNOWN) {
            return needsReview(
                tentativeType = FinancialTransactionType.CREDIT_CARD_PAYMENT,
                transferOwnership = ownership,
                reasons = listOf("card_payment_ownership_unresolved"),
            )
        }

        // MessageFamily.CARD_PAYMENT already established card-payment evidence.
        // Owned account → owned referenced card is enough; do not require invented CardType.
        if (isOwnedAccountToOwnedCardPaymentShape(source, destination)) {
            return classified(
                type = FinancialTransactionType.CREDIT_CARD_PAYMENT,
                transferOwnership = ownership,
                reasons = listOf(
                    "owned_account_to_owned_credit_card",
                    "not_a_new_expense",
                ),
            )
        }

        return needsReview(
            tentativeType = FinancialTransactionType.CREDIT_CARD_PAYMENT,
            transferOwnership = ownership,
            reasons = listOf("card_payment_family_without_owned_account_to_credit_card_shape"),
        )
    }

    private fun classifyTransfer(evidence: ClassificationEvidence): ClassificationResult {
        val source = evidence.source
        val destination = evidence.destination

        if (source == null || destination == null) {
            return needsReview(
                tentativeType = null,
                transferOwnership = TransferOwnershipResolver.resolve(
                    source?.ownership,
                    destination?.ownership,
                ),
                reasons = listOf("transfer_missing_source_or_destination"),
            )
        }

        // Owned account → owned credit card is liability settlement, not a self-transfer
        // between equivalent cash containers (D-007), even if SMS looks like a transfer.
        // Requires a genuinely known CardType.CREDIT on the destination.
        if (isOwnedCashLikeToOwnedCreditCard(source, destination)) {
            return classified(
                type = FinancialTransactionType.CREDIT_CARD_PAYMENT,
                transferOwnership = TransferOwnershipType.SELF_TRANSFER,
                reasons = listOf(
                    "owned_account_to_owned_credit_card",
                    "network_ignored_for_ownership",
                ),
            )
        }

        val ownership = TransferOwnershipResolver.resolve(source.ownership, destination.ownership)

        return when (ownership) {
            TransferOwnershipType.SELF_TRANSFER ->
                classified(
                    type = FinancialTransactionType.SELF_TRANSFER,
                    transferOwnership = ownership,
                    reasons = listOf(
                        "owned_to_owned",
                        "network_ignored_for_ownership",
                    ),
                )

            TransferOwnershipType.EXTERNAL_INCOMING ->
                classified(
                    type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                    transferOwnership = ownership,
                    reasons = listOf(
                        "external_to_owned",
                        "not_automatically_income",
                        "network_ignored_for_ownership",
                    ),
                )

            TransferOwnershipType.EXTERNAL_OUTGOING ->
                classified(
                    type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                    transferOwnership = ownership,
                    reasons = listOf(
                        "owned_to_external",
                        "not_automatically_expense",
                        "network_ignored_for_ownership",
                    ),
                )

            TransferOwnershipType.UNKNOWN ->
                needsReview(
                    tentativeType = null,
                    transferOwnership = ownership,
                    reasons = listOf("transfer_ownership_unknown_no_guess"),
                )
        }
    }

    /**
     * CARD_PAYMENT family: owned account (or known debit card) → owned card.
     * Card type need not be known; the message family already classified the event.
     */
    private fun isOwnedAccountToOwnedCardPaymentShape(
        source: ResolvedContainerFacts,
        destination: ResolvedContainerFacts,
    ): Boolean {
        val sourceIsOwnedCashLike =
            source.ownership == OwnershipStatus.OWNED &&
                (
                    source.kind == ContainerKind.ACCOUNT ||
                        (source.kind == ContainerKind.CARD && source.knownCardType == CardType.DEBIT)
                    )
        val destinationIsOwnedCard =
            destination.kind == ContainerKind.CARD &&
                destination.ownership == OwnershipStatus.OWNED
        return sourceIsOwnedCashLike && destinationIsOwnedCard
    }

    /**
     * Transfer-family special case: only when credit-card type is genuinely known.
     */
    private fun isOwnedCashLikeToOwnedCreditCard(
        source: ResolvedContainerFacts,
        destination: ResolvedContainerFacts,
    ): Boolean =
        isOwnedAccountToOwnedCardPaymentShape(source, destination) &&
            destination.knownCardType == CardType.CREDIT

    private fun classified(
        type: FinancialTransactionType,
        transferOwnership: TransferOwnershipType?,
        reasons: List<String>,
    ): ClassificationResult.Classified =
        ClassificationResult.Classified(
            transactionType = type,
            transferOwnership = transferOwnership,
            impact = FinancialImpactCalculator.forType(type),
            reasons = reasons,
        )

    private fun needsReview(
        tentativeType: FinancialTransactionType?,
        transferOwnership: TransferOwnershipType?,
        reasons: List<String>,
    ): ClassificationResult.NeedsReview =
        ClassificationResult.NeedsReview(
            tentativeType = tentativeType,
            transferOwnership = transferOwnership,
            impact = tentativeType?.let { FinancialImpactCalculator.forType(it) }
                ?: FinancialImpactCalculator.unresolved(),
            reasons = reasons,
        )
}
