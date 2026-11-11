package com.baraa.masroof.application.review

import com.baraa.masroof.application.transaction.ReconciliationReport
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.sms.time.InstantClock

/**
 * Applies a [ReconciliationReport] to durable review_item rows.
 *
 * Idempotent: one review per RawSms; createdAt preserved; reasons/kind refreshed.
 */
class ReviewQueueUpdater(
    private val reviewRepository: ReviewRepository,
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val clock: InstantClock,
) {
    suspend fun applyReport(report: ReconciliationReport) {
        val now = clock.now()
        val candidateIds = report.reviewCandidates.map { it.rawSmsId }.toSet()

        for (candidate in report.reviewCandidates) {
            reviewRepository.upsertRequired(
                rawSmsId = candidate.rawSmsId,
                kind = candidate.kind,
                reasons = candidate.reasons,
                now = now,
            )
        }

        for (rawSmsId in report.settledRawSmsIds) {
            if (rawSmsId in candidateIds) continue
            val existing = reviewRepository.findByRawSmsId(rawSmsId) ?: continue
            if (existing.status != ReviewStatus.REQUIRED) continue
            // Do not overwrite an explicit user resolution.
            if (existing.resolutionKind != null &&
                existing.resolutionKind != ReviewResolutionKind.AUTO_NO_LONGER_REQUIRED
            ) {
                continue
            }
            val txId = financialTransactionRepository.findByRawSmsId(rawSmsId)?.id
            reviewRepository.markResolved(
                id = existing.id,
                resolutionKind = ReviewResolutionKind.AUTO_NO_LONGER_REQUIRED,
                resolvedAt = now,
                resolvedTransactionId = txId,
            )
        }
    }
}
