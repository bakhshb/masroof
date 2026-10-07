package com.baraa.masroof.application.review

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.assembly.TransactionAssembler
import com.baraa.masroof.domain.assembly.TransactionTiming
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.ids.UserCorrectionIdFactory
import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.matching.TransferMatchPair
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.ManualReviewResolutionRepository
import com.baraa.masroof.domain.repository.ManualReviewResolutionResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserCorrectionRepository
import com.baraa.masroof.sms.time.InstantClock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Backend review / user-resolution APIs for future UI.
 * No Compose. No automatic guessing beyond P8 + explicit human decisions.
 */
class ReviewWorkflowService(
    private val reviewRepository: ReviewRepository,
    private val userCorrectionRepository: UserCorrectionRepository,
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val rawSmsRepository: RawSmsRepository,
    private val ownershipResolver: OwnershipResolver,
    private val ownershipConfirmationService: OwnershipConfirmationService,
    private val effectiveParsedEventProvider: EffectiveParsedEventProvider,
    private val reconciliationService: TransactionReconciliationService,
    private val reviewQueueUpdater: ReviewQueueUpdater,
    private val manualReviewResolutionRepository: ManualReviewResolutionRepository,
    private val clock: InstantClock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val appLogService: AppLogService? = null,
    private val newCorrectionId: () -> String = {
        UserCorrectionIdFactory.create(UUID.randomUUID().toString())
    },
) {
    suspend fun refreshReviewQueue() {
        val report = reconciliationService.reconcileStoredEventsDetailed()
        reviewQueueUpdater.applyReport(report)
    }

    suspend fun listRequiredReviews(): List<ReviewItem> =
        reviewRepository.listRequired()

    suspend fun listIgnoredReviews(): List<ReviewItem> =
        reviewRepository.listIgnored()

    suspend fun getReview(reviewId: String): ReviewItem? =
        reviewRepository.getById(reviewId)

    suspend fun applyCorrection(
        reviewId: String,
        correctedType: MessageFamily? = null,
        correctedAmount: Money? = null,
        correctedMerchant: String? = null,
        correctedCounterparty: String? = null,
    ): ReviewWorkflowResult {
        val review = reviewRepository.getById(reviewId)
            ?: return ReviewWorkflowResult.Rejected("review_not_found")
        if (review.status != ReviewStatus.REQUIRED) {
            return ReviewWorkflowResult.Rejected("review_not_required")
        }
        if (financialTransactionRepository.isRawSmsLinked(review.rawSmsId)) {
            return ReviewWorkflowResult.Rejected("raw_sms_already_finalized")
        }
        if (correctedType == null &&
            correctedAmount == null &&
            correctedMerchant == null &&
            correctedCounterparty == null
        ) {
            return ReviewWorkflowResult.Rejected("empty_correction")
        }

        val now = clock.now()
        val correction = UserCorrection(
            id = newCorrectionId(),
            targetRawSmsId = review.rawSmsId,
            correctedType = correctedType,
            correctedAmount = correctedAmount,
            correctedMerchant = correctedMerchant,
            correctedCounterparty = correctedCounterparty,
            createdAt = now,
        )
        userCorrectionRepository.save(correction)

        val report = reconciliationService.reconcileStoredEventsDetailed()
        reviewQueueUpdater.applyReport(report)

        val updated = reviewRepository.findByRawSmsId(review.rawSmsId)
            ?: return ReviewWorkflowResult.Rejected("review_missing_after_refresh")
        val tx = financialTransactionRepository.findByRawSmsId(review.rawSmsId)
        if (tx != null && updated.status == ReviewStatus.RESOLVED) {
            val marked = reviewRepository.markResolved(
                id = updated.id,
                resolutionKind = ReviewResolutionKind.USER_CORRECTION,
                resolvedAt = clock.now(),
                resolvedTransactionId = tx.id,
            ) ?: return ReviewWorkflowResult.Rejected("review_resolution_failed")
            logReviewAction("Correction applied", reviewId)
            return ReviewWorkflowResult.Success(
                review = marked,
                transaction = tx,
                correction = correction,
            )
        }
        logReviewAction("Correction saved", reviewId)
        return ReviewWorkflowResult.Success(
            review = updated,
            transaction = tx,
            correction = correction,
        )
    }

    suspend fun resolveTransferAsExternal(reviewId: String): ReviewWorkflowResult {
        val review = reviewRepository.getById(reviewId)
            ?: return ReviewWorkflowResult.Rejected("review_not_found")
        if (review.status != ReviewStatus.REQUIRED) {
            return ReviewWorkflowResult.Rejected("review_not_required")
        }
        if (financialTransactionRepository.isRawSmsLinked(review.rawSmsId)) {
            return ReviewWorkflowResult.Rejected("raw_sms_already_finalized")
        }
        val record = effectiveParsedEventProvider.findEffectiveByRawSmsId(review.rawSmsId)
            ?: return ReviewWorkflowResult.Rejected("parsed_event_missing")
        val event = record.event
        val amount = event.amount
            ?: return ReviewWorkflowResult.Rejected("missing_amount")
        val raw = rawSmsRepository.getById(review.rawSmsId)
            ?: return ReviewWorkflowResult.Rejected("raw_sms_missing")

        val (type, sourceId, destId) = when (event.messageFamily) {
            MessageFamily.TRANSFER_OUT -> {
                val sourceRef = event.sourceAccountRef
                    ?: return ReviewWorkflowResult.Rejected("missing_source")
                if (ownershipResolver.resolveAccount(sourceRef) != OwnershipStatus.OWNED) {
                    return ReviewWorkflowResult.Rejected("source_not_owned")
                }
                val sourceContainerId = FinancialContainerIdFactory.accountId(sourceRef)
                    ?: return ReviewWorkflowResult.Rejected("source_not_durable")
                Triple(
                    FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                    sourceContainerId,
                    event.destinationAccountRef?.let(FinancialContainerIdFactory::accountId),
                )
            }

            MessageFamily.TRANSFER_IN -> {
                val destRef = event.destinationAccountRef
                    ?: return ReviewWorkflowResult.Rejected("missing_destination")
                if (ownershipResolver.resolveAccount(destRef) != OwnershipStatus.OWNED) {
                    return ReviewWorkflowResult.Rejected("destination_not_owned")
                }
                val destContainerId = FinancialContainerIdFactory.accountId(destRef)
                    ?: return ReviewWorkflowResult.Rejected("destination_not_durable")
                Triple(
                    FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                    event.sourceAccountRef?.let(FinancialContainerIdFactory::accountId),
                    destContainerId,
                )
            }

            else -> return ReviewWorkflowResult.Rejected("not_a_transfer")
        }

        val (occurredAt, occurredAtZone) = resolvedTransactionTime(
            event = event,
            occurredAtLocal = record.details.occurredAtLocal,
            receivedAt = raw.receivedAt,
        )
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf(review.rawSmsId)),
            type = type,
            amount = amount,
            occurredAt = occurredAt,
            sourceContainerId = sourceId,
            destinationContainerId = destId,
            merchant = event.merchant,
            counterparty = event.counterparty,
            categoryId = null,
            linkedParsedEventIds = listOf(event.id),
            occurredAtZone = occurredAtZone,
        )
        return mapPersistResult(
            manualReviewResolutionRepository.persistSingleResolution(
                transaction = tx,
                rawSmsIds = listOf(review.rawSmsId),
                reviewId = review.id,
                resolutionKind = ReviewResolutionKind.USER_EXTERNAL_TRANSFER,
                resolvedAt = clock.now(),
            ),
        ).also { result ->
            if (result is ReviewWorkflowResult.Success) {
                logReviewAction("Resolved external transfer", reviewId)
            }
        }
    }

    suspend fun resolveSelfTransferPair(
        outgoingReviewId: String,
        incomingReviewId: String,
    ): ReviewWorkflowResult {
        val outReview = reviewRepository.getById(outgoingReviewId)
            ?: return ReviewWorkflowResult.Rejected("outgoing_review_not_found")
        val inReview = reviewRepository.getById(incomingReviewId)
            ?: return ReviewWorkflowResult.Rejected("incoming_review_not_found")
        if (outReview.status != ReviewStatus.REQUIRED || inReview.status != ReviewStatus.REQUIRED) {
            return ReviewWorkflowResult.Rejected("review_not_required")
        }
        if (outReview.rawSmsId == inReview.rawSmsId) {
            return ReviewWorkflowResult.Rejected("same_raw_sms")
        }
        if (financialTransactionRepository.isRawSmsLinked(outReview.rawSmsId) ||
            financialTransactionRepository.isRawSmsLinked(inReview.rawSmsId)
        ) {
            return ReviewWorkflowResult.Rejected("raw_sms_already_finalized")
        }

        val outRecord = effectiveParsedEventProvider.findEffectiveByRawSmsId(outReview.rawSmsId)
            ?: return ReviewWorkflowResult.Rejected("outgoing_event_missing")
        val inRecord = effectiveParsedEventProvider.findEffectiveByRawSmsId(inReview.rawSmsId)
            ?: return ReviewWorkflowResult.Rejected("incoming_event_missing")
        val outEvent = outRecord.event
        val inEvent = inRecord.event
        if (outEvent.messageFamily != MessageFamily.TRANSFER_OUT ||
            inEvent.messageFamily != MessageFamily.TRANSFER_IN
        ) {
            return ReviewWorkflowResult.Rejected("pair_family_mismatch")
        }
        val outAmount = outEvent.amount
            ?: return ReviewWorkflowResult.Rejected("missing_amount")
        val inAmount = inEvent.amount
            ?: return ReviewWorkflowResult.Rejected("missing_amount")
        if (outAmount != inAmount) {
            return ReviewWorkflowResult.Rejected("amount_mismatch")
        }

        val outSource = outEvent.sourceAccountRef
            ?: return ReviewWorkflowResult.Rejected("missing_source")
        val inDest = inEvent.destinationAccountRef
            ?: return ReviewWorkflowResult.Rejected("missing_destination")
        if (ownershipResolver.resolveAccount(outSource) != OwnershipStatus.OWNED) {
            return ReviewWorkflowResult.Rejected("source_not_owned")
        }
        if (ownershipResolver.resolveAccount(inDest) != OwnershipStatus.OWNED) {
            return ReviewWorkflowResult.Rejected("destination_not_owned")
        }
        if (FinancialContainerIdFactory.accountId(outSource) == null ||
            FinancialContainerIdFactory.accountId(inDest) == null
        ) {
            return ReviewWorkflowResult.Rejected("endpoints_not_durable")
        }

        val outRaw = rawSmsRepository.getById(outReview.rawSmsId)
            ?: return ReviewWorkflowResult.Rejected("outgoing_raw_sms_missing")
        val inRaw = rawSmsRepository.getById(inReview.rawSmsId)
            ?: return ReviewWorkflowResult.Rejected("incoming_raw_sms_missing")

        val pair = TransferMatchPair(
            outgoing = TransferMatchCandidate(
                event = outEvent,
                transactionReference = outRecord.details.transactionReference,
                occurredAtLocal = outRecord.details.occurredAtLocal,
                receivedAt = outRaw.receivedAt,
                sourceOwnership = OwnershipStatus.OWNED,
                destinationOwnership = OwnershipStatus.UNKNOWN,
            ),
            incoming = TransferMatchCandidate(
                event = inEvent,
                transactionReference = inRecord.details.transactionReference,
                occurredAtLocal = inRecord.details.occurredAtLocal,
                receivedAt = inRaw.receivedAt,
                sourceOwnership = OwnershipStatus.UNKNOWN,
                destinationOwnership = OwnershipStatus.OWNED,
            ),
        )
        val outcome = TransactionAssembler.assembleMatchedPair(
            pair = pair,
            outgoingSourceOwnership = OwnershipStatus.OWNED,
            incomingDestinationOwnership = OwnershipStatus.OWNED,
        )
        if (outcome !is TransactionAssembler.Outcome.Assembled) {
            return ReviewWorkflowResult.Rejected("pair_assembly_failed")
        }

        return mapPersistResult(
            manualReviewResolutionRepository.persistPairResolution(
                transaction = outcome.transaction,
                rawSmsIds = outcome.rawSmsIds,
                firstReviewId = outReview.id,
                secondReviewId = inReview.id,
                resolutionKind = ReviewResolutionKind.USER_SELF_TRANSFER_PAIR,
                resolvedAt = clock.now(),
            ),
        ).also { result ->
            if (result is ReviewWorkflowResult.Success) {
                logReviewAction(
                    "Resolved self-transfer pair",
                    "${AppLogFormatting.maskId(outgoingReviewId)}/${AppLogFormatting.maskId(incomingReviewId)}",
                )
            }
        }
    }

    suspend fun resolveAsIgnored(reviewId: String): ReviewWorkflowResult =
        resolveAsNonFinancial(reviewId)

    suspend fun resolveAsNonFinancial(reviewId: String): ReviewWorkflowResult {
        val review = reviewRepository.getById(reviewId)
            ?: return ReviewWorkflowResult.Rejected("review_not_found")
        if (review.status != ReviewStatus.REQUIRED) {
            return ReviewWorkflowResult.Rejected("review_not_required")
        }
        if (financialTransactionRepository.isRawSmsLinked(review.rawSmsId)) {
            return ReviewWorkflowResult.Rejected("raw_sms_already_finalized")
        }
        val marked = reviewRepository.markResolved(
            id = review.id,
            resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL,
            resolvedAt = clock.now(),
            resolvedTransactionId = null,
        ) ?: return ReviewWorkflowResult.Rejected("review_resolution_failed")
        logReviewAction("Dismissed as non-financial", reviewId)
        return ReviewWorkflowResult.Success(review = marked, transaction = null)
    }

    suspend fun resolveAsFinancialType(
        reviewId: String,
        type: FinancialTransactionType,
    ): ReviewWorkflowResult {
        if (type !in ALLOWED_MANUAL_SINGLE_TYPES) {
            return ReviewWorkflowResult.Rejected("type_not_allowed_for_single_resolution")
        }
        val review = reviewRepository.getById(reviewId)
            ?: return ReviewWorkflowResult.Rejected("review_not_found")
        if (review.status != ReviewStatus.REQUIRED) {
            return ReviewWorkflowResult.Rejected("review_not_required")
        }
        if (financialTransactionRepository.isRawSmsLinked(review.rawSmsId)) {
            return ReviewWorkflowResult.Rejected("raw_sms_already_finalized")
        }
        val record = effectiveParsedEventProvider.findEffectiveByRawSmsId(review.rawSmsId)
            ?: return ReviewWorkflowResult.Rejected("parsed_event_missing")
        val event = record.event
        val amount = event.amount
            ?: return ReviewWorkflowResult.Rejected("missing_amount")
        val raw = rawSmsRepository.getById(review.rawSmsId)
            ?: return ReviewWorkflowResult.Rejected("raw_sms_missing")

        val sourceAccountId = event.sourceAccountRef?.let(FinancialContainerIdFactory::accountId)
        val destAccountId = event.destinationAccountRef?.let(FinancialContainerIdFactory::accountId)
        val cardId = event.cardRef?.let(FinancialContainerIdFactory::cardId)

        val (sourceId, destId) = when (type) {
            FinancialTransactionType.EXPENSE ->
                (cardId ?: sourceAccountId) to null

            FinancialTransactionType.INCOME ->
                null to destAccountId

            FinancialTransactionType.CREDIT_CARD_PAYMENT ->
                sourceAccountId to cardId

            FinancialTransactionType.REFUND ->
                null to (destAccountId ?: cardId)

            FinancialTransactionType.CASH_WITHDRAWAL,
            FinancialTransactionType.BILL_PAYMENT,
            FinancialTransactionType.FEE,
            ->
                sourceAccountId to null

            FinancialTransactionType.LOAN_REPAYMENT -> {
                val loanType = record.details.loanType
                    ?: return ReviewWorkflowResult.Rejected("loan_type_missing")
                maybeConfirmLoanOwned(LoanReference(event.bank, loanType))
                sourceAccountId to FinancialContainerIdFactory.loanId(event.bank, loanType)
            }

            FinancialTransactionType.ADJUSTMENT ->
                sourceAccountId to destAccountId

            else ->
                return ReviewWorkflowResult.Rejected("type_not_allowed_for_single_resolution")
        }

        val (occurredAt, occurredAtZone) = resolvedTransactionTime(
            event = event,
            occurredAtLocal = record.details.occurredAtLocal,
            receivedAt = raw.receivedAt,
        )
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf(review.rawSmsId)),
            type = type,
            amount = amount,
            occurredAt = occurredAt,
            sourceContainerId = sourceId,
            destinationContainerId = destId,
            merchant = event.merchant,
            counterparty = event.counterparty,
            categoryId = null,
            linkedParsedEventIds = listOf(event.id),
            occurredAtZone = occurredAtZone,
        )
        return mapPersistResult(
            manualReviewResolutionRepository.persistSingleResolution(
                transaction = tx,
                rawSmsIds = listOf(review.rawSmsId),
                reviewId = review.id,
                resolutionKind = ReviewResolutionKind.USER_FINANCIAL_TYPE,
                resolvedAt = clock.now(),
            ),
        ).also { result ->
            if (result is ReviewWorkflowResult.Success) {
                logReviewAction("Resolved as ${type.name.lowercase()}", reviewId)
            }
        }
    }

    /**
     * Same instant and zone as automatic reconciliation: fixed bank zone, then the
     * bank-local wall clock, then SMS receipt time.
     */
    private fun resolvedTransactionTime(
        event: ParsedEvent,
        occurredAtLocal: LocalDateTime?,
        receivedAt: Instant,
    ): Pair<Instant, String> {
        val zone = TransactionTiming.zoneFor(bank = event.bank, fallback = zoneId)
        val occurredAt = TransactionTiming.effectiveOccurredAt(
            event = event,
            occurredAtLocal = occurredAtLocal,
            receivedAt = receivedAt,
            zoneId = zoneId,
        )
        return occurredAt to zone.id
    }

    private fun logReviewAction(action: String, reviewId: String) {
        appLogService?.info(
            AppLogCategories.REVIEW,
            "$action for review ${AppLogFormatting.maskId(reviewId)}",
        )
    }

    private fun mapPersistResult(result: ManualReviewResolutionResult): ReviewWorkflowResult =
        when (result) {
            is ManualReviewResolutionResult.Success ->
                ReviewWorkflowResult.Success(
                    review = result.reviews.first(),
                    transaction = result.transaction,
                    pairedReview = result.reviews.getOrNull(1),
                )

            is ManualReviewResolutionResult.Conflict ->
                ReviewWorkflowResult.Rejected(
                    when {
                        result.existingTransactionId != null -> "raw_sms_already_finalized"
                        else -> result.reason
                    },
                )

            is ManualReviewResolutionResult.Failed ->
                ReviewWorkflowResult.Rejected(result.reason)
        }

    private suspend fun maybeConfirmLoanOwned(reference: LoanReference) {
        if (ownershipResolver.resolveLoan(reference) != OwnershipStatus.UNKNOWN) return
        ownershipConfirmationService.confirmLoanOwned(reference)
    }

    companion object {
        val ALLOWED_MANUAL_SINGLE_TYPES: Set<FinancialTransactionType> = setOf(
            FinancialTransactionType.EXPENSE,
            FinancialTransactionType.INCOME,
            FinancialTransactionType.CREDIT_CARD_PAYMENT,
            FinancialTransactionType.REFUND,
            FinancialTransactionType.CASH_WITHDRAWAL,
            FinancialTransactionType.BILL_PAYMENT,
            FinancialTransactionType.FEE,
            FinancialTransactionType.LOAN_REPAYMENT,
            FinancialTransactionType.ADJUSTMENT,
        )
    }
}
