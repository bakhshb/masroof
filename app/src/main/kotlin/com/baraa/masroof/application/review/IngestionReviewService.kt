package com.baraa.masroof.application.review

import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.sms.time.InstantClock

/**
 * Durable review rows for recognized-bank RawSms that ingestion could not turn
 * into an automatically usable ParsedEvent (Unsupported, Invalid, ReviewRequired
 * without an event, or a processing failure after RawSms persistence).
 *
 * Keyed by rawSmsId like every other review; RESOLVED rows are never reopened.
 * Never parses text or assembles transactions. Reconciliation only
 * auto-resolves these rows once later evidence (e.g. a successful reparse)
 * settles the same RawSms.
 */
class IngestionReviewService(
    private val reviewRepository: ReviewRepository,
    private val clock: InstantClock,
) {
    suspend fun requireReview(rawSmsId: String, reason: String): ReviewItem =
        reviewRepository.upsertRequired(
            rawSmsId = rawSmsId,
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf(reason),
            now = clock.now(),
        )

    companion object {
        const val REASON_UNSUPPORTED_FORMAT = "unsupported_bank_message_format"
        const val REASON_INVALID_PARSED_EVENT = "invalid_parsed_event"
        const val REASON_PARSE_REVIEW_REQUIRED = "parse_review_required"
        const val REASON_PROCESSING_ERROR = "processing_error"
    }
}
