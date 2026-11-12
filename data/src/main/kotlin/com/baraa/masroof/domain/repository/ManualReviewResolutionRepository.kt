package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewResolutionKind
import java.time.Instant

/**
 * Atomic persistence boundary for manual user review resolutions.
 *
 * Coordinates FinancialTransaction (+ RawSms links) and ReviewItem resolution
 * in one database transaction. Does not contain workflow validation rules.
 */
interface ManualReviewResolutionRepository {
    suspend fun persistSingleResolution(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        reviewId: String,
        resolutionKind: ReviewResolutionKind,
        resolvedAt: Instant,
        validateBeforeWrite: suspend () -> Boolean = { true },
    ): ManualReviewResolutionResult

    suspend fun persistPairResolution(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        firstReviewId: String,
        secondReviewId: String,
        resolutionKind: ReviewResolutionKind,
        resolvedAt: Instant,
    ): ManualReviewResolutionResult
}

sealed interface ManualReviewResolutionResult {
    data class Success(
        val transaction: FinancialTransaction,
        val reviews: List<ReviewItem>,
    ) : ManualReviewResolutionResult

    data class Conflict(
        val reason: String,
        val rawSmsId: String? = null,
        val existingTransactionId: String? = null,
    ) : ManualReviewResolutionResult

    data class Failed(
        val reason: String,
    ) : ManualReviewResolutionResult
}
