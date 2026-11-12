package com.baraa.masroof.application.ingestion

import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.sms.HistoricalBatchRecoveryScheduler
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.CancellationException

/**
 * Records a pass that did not finish in `processing_retry`, the raw SMS ids still to project.
 * (Parse paths also write `HISTORICAL_BATCH` intent in their parse transaction; the recovery pass clears it.)
 *
 * A pass that did not finish leaves the ledger as it was for its ids, so recovery only has to
 * run the pass again for them: [markExhausted] hands one SMS back to the live worker at startup,
 * [retryLater] hands a set to the one historical recovery pass ([com.baraa.masroof.application.sms.HistoricalDerivedRecovery]).
 * An SMS the user decided posts nothing ([com.baraa.masroof.domain.model.EffectiveUserDecisions.isNonFinancial])
 * stays closed. Other resolved reviews stay resolved; recoverability is the retry row.
 * Writing a row throws when persistence fails so callers can retry.
 */
class ProcessingRecovery(
    private val processingRetryRepository: ProcessingRetryRepository,
    private val reviewRepository: ReviewRepository,
    private val userDecisionRepository: UserDecisionRepository,
    private val ingestionReviewService: IngestionReviewService,
    private val clock: InstantClock,
    private val historicalRecoveryScheduler: HistoricalBatchRecoveryScheduler,
) {
    /** Live retries ran out: a `LIVE` row for startup and a `processing_error` review unless the review is resolved. */
    suspend fun markExhausted(rawSmsId: String) {
        if (userDecisionRepository.effectiveFor(rawSmsId).isNonFinancial) return
        val review = reviewRepository.findByRawSmsId(rawSmsId)
        if (review?.status != ReviewStatus.RESOLVED) {
            ingestionReviewService.requireReview(
                rawSmsId,
                IngestionReviewService.REASON_PROCESSING_ERROR,
            )
        }
        processingRetryRepository.markRequired(rawSmsId, clock.now(), ProcessingRetryMode.LIVE)
    }

    /**
     * Writes [rawSmsIds] as one `HISTORICAL_BATCH` set in one transaction, then enqueues the one
     * recovery pass. A failed write throws without accepting a prefix of the set; a failed enqueue
     * is ignored because the set is durable and startup enqueues the pass.
     */
    suspend fun retryLater(rawSmsIds: Collection<String>) {
        val ids = rawSmsIds.distinct()
        if (ids.isEmpty()) return
        val decisions = userDecisionRepository.effectiveFor(ids)
        val affected = ids.filterNot { decisions.getValue(it).isNonFinancial }
        if (affected.isEmpty()) return
        processingRetryRepository.markRequired(affected, clock.now(), ProcessingRetryMode.HISTORICAL_BATCH)
        try {
            historicalRecoveryScheduler.schedule()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The retry set is durable. Startup enqueues the one batch worker.
        }
    }

    suspend fun clear(rawSmsIds: List<String>) {
        processingRetryRepository.clear(rawSmsIds)
    }
}
