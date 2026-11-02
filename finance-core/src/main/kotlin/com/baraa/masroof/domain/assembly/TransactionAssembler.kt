package com.baraa.masroof.domain.assembly

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.matching.TransferMatchPair
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.rules.ClassificationEvidence
import com.baraa.masroof.domain.rules.ClassificationResult
import com.baraa.masroof.domain.rules.ContainerKind
import com.baraa.masroof.domain.rules.ResolvedContainerFacts
import com.baraa.masroof.domain.rules.TransactionClassifier
import java.time.Instant

/**
 * Pure assembly of [FinancialTransaction] from validated evidence + ownership.
 * No Room / Android.
 *
 * Passes only facts P8 genuinely knows into P2 classification — never fabricates
 * temporary account/card domain objects or invented account/card types solely to
 * satisfy the classifier. Container ids come from [FinancialContainerIdFactory]
 * on durable known-bank refs.
 */
object TransactionAssembler {
    sealed interface Outcome {
        data class Assembled(
            val transaction: FinancialTransaction,
            val rawSmsIds: List<String>,
        ) : Outcome

        data object PendingMatch : Outcome

        data class NeedsReview(
            val reasons: List<String>,
        ) : Outcome

        data object Ignored : Outcome
    }

    const val REASON_PARSE_REVIEW_REQUIRED = "parse_review_required"
    const val REASON_PARSE_PARTIAL = "parse_partial"
    const val REASON_INVALID_PARSED_EVENT = "invalid_parsed_event"
    const val REASON_UNSUPPORTED_FORMAT = "unsupported_bank_message_format"

    /**
     * Hard automation boundary on [ParseStatus].
     *
     * Only [ParseStatus.SUCCESS] parse output may create or update a
     * [FinancialTransaction] automatically. [userConfirmed] is true only when a
     * human correction exists for the evidence; it is never inferred.
     */
    fun isAutomationEligible(event: ParsedEvent, userConfirmed: Boolean = false): Boolean =
        userConfirmed || event.parseStatus == ParseStatus.SUCCESS

    /** Durable review reason for a parse status that blocks automation, or null for SUCCESS / NON_FINANCIAL. */
    fun parseStatusReviewReason(status: ParseStatus): String? =
        when (status) {
            ParseStatus.SUCCESS,
            ParseStatus.NON_FINANCIAL,
            -> null

            ParseStatus.REVIEW_REQUIRED -> REASON_PARSE_REVIEW_REQUIRED
            ParseStatus.PARTIAL -> REASON_PARSE_PARTIAL
            ParseStatus.INVALID -> REASON_INVALID_PARSED_EVENT
            ParseStatus.UNSUPPORTED -> REASON_UNSUPPORTED_FORMAT
        }

    /**
     * Assemble a single ParsedEvent when possible.
     *
     * [sourceOwnership]/[destinationOwnership]/[cardOwnership] come from P7.
     * [receivedAt] is the RawSms receipt fallback clock.
     * [userConfirmed] lifts the [ParseStatus] gate for user-corrected evidence only.
     */
    fun assembleSingle(
        event: ParsedEvent,
        receivedAt: Instant,
        sourceOwnership: OwnershipStatus,
        destinationOwnership: OwnershipStatus,
        cardOwnership: OwnershipStatus,
        loanOwnership: OwnershipStatus = OwnershipStatus.UNKNOWN,
        loanType: LoanType? = null,
        transactionOccurredAt: Instant = receivedAt,
        userConfirmed: Boolean = false,
        occurredAtZone: java.time.ZoneId? = null,
        deferIntraBankPairing: Boolean = true,
    ): Outcome {
        when (event.messageFamily) {
            MessageFamily.OTP,
            MessageFamily.BALANCE_NOTICE,
            MessageFamily.NON_FINANCIAL,
            -> return Outcome.Ignored

            MessageFamily.UNKNOWN ->
                return Outcome.NeedsReview(listOf("unknown_message_family"))

            else -> Unit
        }

        if (!isAutomationEligible(event, userConfirmed)) {
            val reason = parseStatusReviewReason(event.parseStatus)
                ?: return Outcome.Ignored
            return Outcome.NeedsReview(
                listOfNotNull(reason, "missing_amount".takeIf { event.amount == null }),
            )
        }

        if (event.amount == null) return Outcome.NeedsReview(listOf("missing_amount"))

        // Transfers with an UNKNOWN-bank side cannot be finalized without a match.
        if (event.messageFamily == MessageFamily.TRANSFER_OUT ||
            event.messageFamily == MessageFamily.TRANSFER_IN
        ) {
            if (hasUnknownBankSide(event)) {
                return Outcome.PendingMatch
            }
        }

        val sourceFacts = accountFacts(event.sourceAccountRef, sourceOwnership)
        val destinationFacts = when (event.messageFamily) {
            MessageFamily.CARD_PAYMENT ->
                cardFacts(event.cardRef, cardOwnership)
                    ?: accountFacts(event.destinationAccountRef, destinationOwnership)

            MessageFamily.FINANCING_INSTALLMENT ->
                loanFacts(event.bank, loanType, loanOwnership)

            else -> accountFacts(event.destinationAccountRef, destinationOwnership)
        }
        val instrumentFacts = when (event.messageFamily) {
            MessageFamily.PURCHASE ->
                cardFacts(event.cardRef, cardOwnership)
                    ?: accountFacts(event.sourceAccountRef, sourceOwnership)

            else -> null
        }

        val classification = TransactionClassifier.classify(
            ClassificationEvidence(
                messageFamily = event.messageFamily,
                source = sourceFacts,
                destination = destinationFacts,
                instrument = instrumentFacts,
                purchaseChannel = event.purchaseChannel,
                bankNetworkType = event.bankNetworkType,
                loanType = loanType,
            ),
        )

        val type = when (classification) {
            is ClassificationResult.Classified if deferIntraBankPairing &&
                shouldDeferIntraBankUntilPairing(event, classification.transactionType) ->
                return Outcome.PendingMatch

            is ClassificationResult.Classified -> classification.transactionType

            // Conservative purchase: family alone is enough for EXPENSE when amount exists.
            is ClassificationResult.NeedsReview if event.messageFamily == MessageFamily.PURCHASE &&
                classification.tentativeType == FinancialTransactionType.EXPENSE ->
                FinancialTransactionType.EXPENSE

            is ClassificationResult.NeedsReview if event.messageFamily == MessageFamily.TRANSFER_IN ||
                event.messageFamily == MessageFamily.TRANSFER_OUT ->
                return Outcome.PendingMatch

            is ClassificationResult.NeedsReview -> return Outcome.NeedsReview(classification.reasons)
        }
        return buildTransaction(
            type = type,
            occurredAt = transactionOccurredAt,
            event = event,
            loanType = loanType,
            occurredAtZone = occurredAtZone,
        )
    }

    /**
     * Fallback after pair matching failed: post a single owned-side transfer
     * as external in/out instead of waiting forever for a counterpart SMS.
     *
     * Pairing still wins when both events are present in the same reconcile pass.
     * An owned → owned intra-bank leg stays pending while a counterpart is also
     * unmatched, so ambiguous same-window legs are not posted as four self-transfers.
     * A lone owned-owned leg may post so a later counterpart can heal it.
     * Local side not OWNED stays pending.
     *
     * Callers pass only candidates that already passed [isAutomationEligible], including a
     * user's financial confirmation, so the owned-owned leg is not gated a second time.
     */
    fun assembleUnmatchedOwnedTransfer(
        candidate: TransferMatchCandidate,
        pendingCounterparts: List<TransferMatchCandidate> = emptyList(),
        fallbackZone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Outcome {
        val event = candidate.event
        val receivedAt = candidate.receivedAt
        val sourceOwnership = candidate.sourceOwnership
        val destinationOwnership = candidate.destinationOwnership
        when (event.messageFamily) {
            MessageFamily.TRANSFER_OUT,
            MessageFamily.TRANSFER_IN,
            -> Unit
            else -> return Outcome.PendingMatch
        }

        if (event.amount == null) return Outcome.NeedsReview(listOf("missing_amount"))

        if (TransactionMatcher.hasPendingIntraBankCounterpart(candidate, pendingCounterparts)) {
            return Outcome.PendingMatch
        }
        if (sourceOwnership == OwnershipStatus.OWNED &&
            destinationOwnership == OwnershipStatus.OWNED
        ) {
            val transactionOccurredAt = TransactionTiming.effectiveOccurredAt(candidate, fallbackZone)
            return assembleSingle(
                event = event,
                receivedAt = receivedAt,
                sourceOwnership = sourceOwnership,
                destinationOwnership = destinationOwnership,
                cardOwnership = OwnershipStatus.UNKNOWN,
                transactionOccurredAt = transactionOccurredAt,
                occurredAtZone = TransactionTiming.zoneFor(event.bank, fallback = fallbackZone),
                deferIntraBankPairing = false,
                userConfirmed = true,
            )
        }

        val transactionOccurredAt = TransactionTiming.effectiveOccurredAt(candidate, fallbackZone)
        val zone = TransactionTiming.zoneFor(event.bank, fallback = fallbackZone)

        val type = when (event.messageFamily) {
            MessageFamily.TRANSFER_OUT -> {
                if (sourceOwnership != OwnershipStatus.OWNED) {
                    return Outcome.PendingMatch
                }
                val sourceRef = event.sourceAccountRef
                    ?: return Outcome.NeedsReview(listOf("missing_source"))
                FinancialContainerIdFactory.accountId(sourceRef)
                    ?: return Outcome.NeedsReview(listOf("source_not_durable"))
                FinancialTransactionType.EXTERNAL_TRANSFER_OUT
            }

            MessageFamily.TRANSFER_IN -> {
                if (destinationOwnership != OwnershipStatus.OWNED) {
                    return Outcome.PendingMatch
                }
                val destRef = event.destinationAccountRef
                    ?: return Outcome.NeedsReview(listOf("missing_destination"))
                FinancialContainerIdFactory.accountId(destRef)
                    ?: return Outcome.NeedsReview(listOf("destination_not_durable"))
                FinancialTransactionType.EXTERNAL_TRANSFER_IN
            }
        }
        return buildTransaction(
            type = type,
            occurredAt = transactionOccurredAt,
            event = event,
            occurredAtZone = zone,
        )
    }

    /**
     * Assemble a mutually unique OUT↔IN pair into one SELF_TRANSFER when both
     * local known-bank endpoints resolve OWNED.
     */
    fun assembleMatchedPair(
        pair: TransferMatchPair,
        outgoingSourceOwnership: OwnershipStatus,
        incomingDestinationOwnership: OwnershipStatus,
        fallbackZone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Outcome {
        if (outgoingSourceOwnership != OwnershipStatus.OWNED) {
            return Outcome.PendingMatch
        }
        if (incomingDestinationOwnership != OwnershipStatus.OWNED) {
            return Outcome.PendingMatch
        }

        val out = pair.outgoing.event
        val inn = pair.incoming.event
        val amount = out.amount ?: return Outcome.NeedsReview(listOf("missing_amount"))
        if (inn.amount == null || amount != inn.amount) {
            return Outcome.NeedsReview(listOf("matched_pair_amount_mismatch"))
        }

        val sourceRef = out.sourceAccountRef
            ?: return Outcome.NeedsReview(listOf("matched_pair_missing_source"))
        val destRef = inn.destinationAccountRef
            ?: return Outcome.NeedsReview(listOf("matched_pair_missing_destination"))
        if (sourceRef.bank == Bank.UNKNOWN || destRef.bank == Bank.UNKNOWN) {
            return Outcome.NeedsReview(listOf("matched_pair_requires_known_bank_endpoints"))
        }

        val sourceId = FinancialContainerIdFactory.accountId(sourceRef)
            ?: return Outcome.NeedsReview(listOf("matched_pair_source_not_durable"))
        val destId = FinancialContainerIdFactory.accountId(destRef)
            ?: return Outcome.NeedsReview(listOf("matched_pair_destination_not_durable"))

        val classification = TransactionClassifier.classify(
            ClassificationEvidence(
                messageFamily = MessageFamily.TRANSFER_OUT,
                source = ResolvedContainerFacts(
                    kind = ContainerKind.ACCOUNT,
                    ownership = OwnershipStatus.OWNED,
                ),
                destination = ResolvedContainerFacts(
                    kind = ContainerKind.ACCOUNT,
                    ownership = OwnershipStatus.OWNED,
                ),
                bankNetworkType = out.bankNetworkType,
            ),
        )

        if (classification !is ClassificationResult.Classified ||
            classification.transactionType != FinancialTransactionType.SELF_TRANSFER
        ) {
            return Outcome.NeedsReview(
                listOf("matched_pair_not_self_transfer") + classification.reasons,
            )
        }

        // Both wall clocks are read in the zone the pair stores, so a later stale-link check that
        // re-reads them with that zone finds the same instant instead of unlinking the pair.
        val zone = TransactionTiming.zoneFor(out.bank, fallback = fallbackZone)
        val occurredAt = listOfNotNull(out.occurredAt, inn.occurredAt).minOrNull()
            ?: listOf(pair.outgoing, pair.incoming)
                .minOfOrNull { TransactionTiming.effectiveOccurredAt(it, fallbackZone, persistedZoneId = zone.id) }
            ?: minOf(pair.outgoing.receivedAt, pair.incoming.receivedAt)

        val rawSmsIds = listOf(out.rawSmsId, inn.rawSmsId).sorted()
        val linked = listOf(out.id, inn.id).sorted()
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(rawSmsIds),
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = amount,
            occurredAt = occurredAt,
            sourceContainerId = sourceId,
            destinationContainerId = destId,
            merchant = null,
            counterparty = out.counterparty ?: inn.counterparty,
            categoryId = null,
            linkedParsedEventIds = linked,
            occurredAtZone = zone.id,
        )
        return Outcome.Assembled(tx, rawSmsIds)
    }

    private fun hasUnknownBankSide(event: ParsedEvent): Boolean =
        event.sourceAccountRef?.bank == Bank.UNKNOWN ||
            event.destinationAccountRef?.bank == Bank.UNKNOWN

    private fun shouldDeferIntraBankUntilPairing(
        event: ParsedEvent,
        transactionType: FinancialTransactionType,
    ): Boolean {
        if (event.messageFamily != MessageFamily.TRANSFER_IN &&
            event.messageFamily != MessageFamily.TRANSFER_OUT
        ) {
            return false
        }
        if (transactionType == FinancialTransactionType.SELF_TRANSFER) {
            return true
        }
        return event.bankNetworkType == BankNetworkType.INTRA_BANK &&
            (
                transactionType == FinancialTransactionType.EXTERNAL_TRANSFER_IN ||
                    transactionType == FinancialTransactionType.EXTERNAL_TRANSFER_OUT
            )
    }

    private fun accountFacts(
        ref: AccountReference?,
        ownership: OwnershipStatus,
    ): ResolvedContainerFacts? {
        if (ref == null) return null
        val masked = ref.maskedNumber?.trim().orEmpty()
        if (masked.isEmpty()) return null
        return ResolvedContainerFacts(
            kind = ContainerKind.ACCOUNT,
            ownership = ownership,
            knownCardType = null,
        )
    }

    private fun loanFacts(
        bank: Bank,
        loanType: com.baraa.masroof.domain.model.LoanType?,
        ownership: OwnershipStatus,
    ): ResolvedContainerFacts? {
        if (loanType == null || bank == Bank.UNKNOWN) return null
        return ResolvedContainerFacts(
            kind = ContainerKind.LOAN,
            ownership = ownership,
        )
    }

    private fun cardFacts(
        ref: CardReference?,
        ownership: OwnershipStatus,
    ): ResolvedContainerFacts? {
        if (ref == null) return null
        val last4 = ref.last4?.trim().orEmpty()
        if (last4.isEmpty()) return null
        // P7 registry does not know CardType — leave knownCardType null.
        return ResolvedContainerFacts(
            kind = ContainerKind.CARD,
            ownership = ownership,
            knownCardType = null,
        )
    }

    private fun buildTransaction(
        type: FinancialTransactionType,
        occurredAt: Instant,
        event: ParsedEvent,
        loanType: LoanType? = null,
        occurredAtZone: java.time.ZoneId? = null,
    ): Outcome.Assembled {
        val rawSmsIds = listOf(event.rawSmsId)
        val zone = occurredAtZone ?: TransactionTiming.zoneFor(event.bank)
        // Resolve durable ids from references only (null for Bank.UNKNOWN / incomplete).
        val durableSourceAccountId = event.sourceAccountRef?.let(FinancialContainerIdFactory::accountId)
        val durableDestAccountId = event.destinationAccountRef?.let(FinancialContainerIdFactory::accountId)
        val durableCardId = event.cardRef?.let(FinancialContainerIdFactory::cardId)

        val (sourceId, destId) = when (event.messageFamily) {
            MessageFamily.PURCHASE ->
                // Mada/debit purchases debit the current account; credit-card-only SMS has no account ref.
                (durableSourceAccountId ?: durableCardId) to null

            MessageFamily.CARD_PAYMENT ->
                durableSourceAccountId to durableCardId

            MessageFamily.FINANCING_INSTALLMENT ->
                durableSourceAccountId to loanType?.let {
                    FinancialContainerIdFactory.loanId(event.bank, it)
                }

            MessageFamily.REFUND ->
                // Incoming value to the user's container — never put the receiver in source.
                null to (durableDestAccountId ?: durableCardId)

            else ->
                durableSourceAccountId to durableDestAccountId
        }

        return Outcome.Assembled(
            transaction = FinancialTransaction.fromSingleEvent(
                event = event,
                type = type,
                occurredAt = occurredAt,
                sourceContainerId = sourceId,
                destinationContainerId = destId,
                occurredAtZone = zone.id,
            ),
            rawSmsIds = rawSmsIds,
        )
    }
}
