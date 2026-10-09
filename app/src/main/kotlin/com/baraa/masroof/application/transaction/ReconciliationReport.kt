package com.baraa.masroof.application.transaction

import com.baraa.masroof.domain.model.ReviewKind

/**
 * Detailed reconciliation output for review-queue updates.
 *
 * [ReconciliationSummary.failed] is the completion signal. Zero means the required
 * writes in this pass did not report a failure. A positive count is incomplete even
 * when other rows posted. [failedRawSmsIds] is the smallest set involved in those
 * failures. Pending-match candidates and intentional non-financial rows are not
 * members of that set.
 */
data class ReconciliationReport(
    val summary: ReconciliationSummary,
    val reviewCandidates: List<ReconciliationReviewCandidate>,
    val settledRawSmsIds: Set<String>,
    val failedRawSmsIds: Set<String> = emptySet(),
)

data class ReconciliationReviewCandidate(
    val rawSmsId: String,
    val kind: ReviewKind,
    val reasons: List<String>,
)
