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
    )

    /**
     * Required-review rows for the list.
     *
     * [reconcile] runs the full-history maintenance sweep. The normal review-list
     * refresh keeps that default. Follow-ups after a scoped correction, restore, or
     * ownership change pass false so the reload only reads the queue those actions
     * already updated.
     */
    suspend fun loadSummaries(reconcile: Boolean = true): List<ReviewSummary> {
        if (reconcile) {
            reviewWorkflowService.refreshReviewQueue()
        }
        val summaries = mutableListOf<ReviewSummary>()
        for (review in reviewWorkflowService.listRequiredReviews()) {
            toSummary(review)?.let { summaries += it }
        }
        return summaries
    }

    suspend fun loadIgnoredSummaries(): List<ReviewSummary> {
        val summaries = mutableListOf<ReviewSummary>()
        for (review in reviewWorkflowService.listIgnoredReviews()) {
            toSummary(review)?.let { summaries += it }
        }
        return summaries
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
        val candidates = mutableListOf<ReviewSummary>()
        for (review in reviewWorkflowService.listRequiredReviews()) {
            if (review.id == forReviewId || review.kind != ReviewKind.PENDING_MATCH) continue
            val summary = toSummary(review) ?: continue
            if (summary.messageFamily == opposite && summary.amount == currentAmount) {
                candidates += summary
            }
        }
        return candidates
    }

    private suspend fun toSummary(review: ReviewItem): ReviewSummary? {
        val detail = toDetail(review) ?: return null
        val title = detail.merchant?.takeIf { it.isNotBlank() }
            ?: detail.counterparty?.takeIf { it.isNotBlank() }
            ?: detail.messageFamily?.name
        return ReviewSummary(
            review = review,
            title = title,
            amount = detail.amount,
            messageFamily = detail.messageFamily,
            receivedAt = detail.receivedAt,
            body = detail.body,
        )
    }

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
        )
    }
}
