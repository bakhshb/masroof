package com.baraa.masroof.application.ingestion

import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.sms.time.InstantClock

/**
 * Persists the retry marker for evidence whose derived processing did not finish.
 *
 * A [ReviewResolutionKind.USER_NON_FINANCIAL] review stays closed.
 * Other resolved reviews stay resolved; recoverability is the processing-retry row.
 * Writing the marker throws when persistence fails so callers can retry.
 */
class ProcessingRecovery(
    private val processingRetryRepository: ProcessingRetryRepository,
    private val reviewRepository: ReviewRepository,
    private val ingestionReviewService: IngestionReviewService,
    private val clock: InstantClock,
) {
    suspend fun markExhausted(rawSmsId: String) {
        val review = reviewRepository.findByRawSmsId(rawSmsId)
        if (review?.status == ReviewStatus.RESOLVED &&
            review.resolutionKind == ReviewResolutionKind.USER_NON_FINANCIAL
        ) {
            return
        }
        if (review?.status != ReviewStatus.RESOLVED) {
            ingestionReviewService.requireReview(
                rawSmsId,
                IngestionReviewService.REASON_PROCESSING_ERROR,
            )
        }
        processingRetryRepository.markRequired(rawSmsId, clock.now())
    }

    suspend fun clear(rawSmsId: String) {
        processingRetryRepository.clear(rawSmsId)
    }
}
