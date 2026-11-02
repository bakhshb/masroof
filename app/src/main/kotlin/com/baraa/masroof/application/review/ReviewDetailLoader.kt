package com.baraa.masroof.application.review

import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.repository.RawSmsRepository
import java.time.Instant

/**
 * Loads review queue rows with display context from RawSms + effective ParsedEvent.
 */
class ReviewDetailLoader(
    private val reviewWorkflowService: ReviewWorkflowService,
    private val rawSmsRepository: RawSmsRepository,
    private val effectiveParsedEventProvider: EffectiveParsedEventProvider,
    private val provenanceWorkflow: com.baraa.masroof.application.interpretation.InterpretationProvenanceWorkflow? = null,
) {
    data class ReviewSummary(
        val review: ReviewItem,
        val title: String?,
        val amount: Money?,
        val messageFamily: MessageFamily?,
        val receivedAt: Instant?,
        val body: String?,
    )

    data class ReviewDetail(
        val review: ReviewItem,
        val sender: String?,
        val body: String?,
        val receivedAt: Instant?,
        val messageFamily: MessageFamily?,
        val amount: Money?,
        val merchant: String?,
        val counterparty: String?,
        val cardRef: CardReference?,
        val provenance: com.baraa.masroof.application.interpretation.InterpretationProvenance? = null,
        val bankId: String? = null,
    )

    /**
     * Required-review rows for the list.
     *
     * Reads the queue already maintained by ingestion, scoped mutations, and
     * [ReviewWorkflowService.refreshReviewQueue]. Opening or reloading the list
     * does not reconcile history.
     */
    suspend fun loadSummaries(): List<ReviewSummary> = summariesOf(reviewWorkflowService.listRequiredReviews())

    /** The ignored list only grows, so its rows are read in batches rather than one message at a time. */
    suspend fun loadIgnoredSummaries(): List<ReviewSummary> = summariesOf(reviewWorkflowService.listIgnoredReviews())

    private suspend fun summariesOf(reviews: List<ReviewItem>): List<ReviewSummary> {
        val ids = reviews.map { it.rawSmsId }
        val raw = rawSmsRepository.getByIds(ids).associateBy { it.id }
        val events = effectiveParsedEventProvider.listEffectiveByRawSmsIds(ids).associateBy { it.event.rawSmsId }
        return reviews.map { review -> summaryOf(review, raw[review.rawSmsId], events[review.rawSmsId]?.event) }
    }

    suspend fun loadDetail(reviewId: String): ReviewDetail? {
        val review = reviewWorkflowService.getReview(reviewId) ?: return null
        return toDetail(review)
    }

    suspend fun loadPairCandidates(forReviewId: String): List<ReviewSummary> {
        val current = reviewWorkflowService.getReview(forReviewId) ?: return emptyList()
        val currentDetail = toDetail(current) ?: return emptyList()
        val currentFamily = currentDetail.messageFamily ?: return emptyList()
        val currentAmount = currentDetail.amount ?: return emptyList()
        val opposite = when (currentFamily) {
            MessageFamily.TRANSFER_OUT -> MessageFamily.TRANSFER_IN
            MessageFamily.TRANSFER_IN -> MessageFamily.TRANSFER_OUT
            else -> return emptyList()
        }
        val pending = reviewWorkflowService.listRequiredReviews()
            .filter { it.id != forReviewId && it.kind == ReviewKind.PENDING_MATCH }
        return summariesOf(pending).filter { it.messageFamily == opposite && it.amount == currentAmount }
    }

    /** List rows need no interpretation provenance; it is read only for an opened detail. */
    private fun summaryOf(review: ReviewItem, raw: com.baraa.masroof.domain.model.RawSms?,
        event: com.baraa.masroof.domain.model.ParsedEvent?): ReviewSummary = ReviewSummary(
        review = review,
        title = event?.merchant?.takeIf { it.isNotBlank() } ?: event?.counterparty?.takeIf { it.isNotBlank() },
        amount = event?.amount,
        messageFamily = event?.messageFamily,
        receivedAt = raw?.receivedAt,
        body = raw?.body,
    )

    private suspend fun toDetail(review: ReviewItem): ReviewDetail? {
        val raw = rawSmsRepository.getById(review.rawSmsId)
        val effective = effectiveParsedEventProvider.findEffectiveByRawSmsId(review.rawSmsId)
        val event = effective?.event
        return ReviewDetail(
            review = review,
            sender = raw?.sender,
            body = raw?.body,
            receivedAt = raw?.receivedAt,
            messageFamily = event?.messageFamily,
            amount = event?.amount,
            merchant = event?.merchant,
            counterparty = event?.counterparty,
            cardRef = event?.cardRef,
            provenance = provenanceWorkflow?.load(review.rawSmsId),
            bankId = (com.baraa.masroof.domain.model.ExplicitBankSelection.selectedBankId(review.reasons)
                ?: event?.bank?.id)?.takeUnless { it == com.baraa.masroof.domain.model.Bank.UNKNOWN.id },
        )
    }
}
