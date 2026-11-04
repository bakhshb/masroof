package com.baraa.masroof.application.ingestion

import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.CancellationException

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
        processingRetryRepository.markRequired(rawSmsId, clock.now(), ProcessingRetryMode.LIVE)
    }

    /**
     * Writes the historical retry set in one transaction.
     * Returns false when every id is already closed as non-financial.
     * Throws when the transaction fails, without accepting a prefix of the set.
     */
    suspend fun markExhaustedBatch(rawSmsIds: List<String>): Boolean {
        if (rawSmsIds.isEmpty()) return false
        val ids = rawSmsIds.distinct()
        val reviews = reviewRepository.findByRawSmsIds(ids)
        val affected = ids.filter { rawSmsId ->
            val review = reviews[rawSmsId]
            review?.status != ReviewStatus.RESOLVED ||
                review.resolutionKind != ReviewResolutionKind.USER_NON_FINANCIAL
        }
        if (affected.isEmpty()) return false
        processingRetryRepository.markRequired(
            affected,
            clock.now(),
            ProcessingRetryMode.HISTORICAL_BATCH,
        )
        return true
    }

    /**
     * Writes the historical retry set, then asks [schedule] to enqueue the batch worker.
     * A failed write throws; a failed schedule is ignored because the set is durable
     * and startup enqueues the one batch worker.
     */
    suspend fun markForHistoricalRetry(rawSmsIds: List<String>, schedule: (() -> Unit)?) {
        if (!markExhaustedBatch(rawSmsIds)) return
        try {
            schedule?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The retry set is durable. Startup enqueues the one batch worker.
        }
    }

    suspend fun clear(rawSmsId: String) {
        processingRetryRepository.clear(rawSmsId)
    }

    suspend fun clearAll(rawSmsIds: List<String>) {
        processingRetryRepository.clear(rawSmsIds)
    }
}
