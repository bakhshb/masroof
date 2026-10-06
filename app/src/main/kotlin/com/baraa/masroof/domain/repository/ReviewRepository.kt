package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import java.time.Instant

/**
 * Persistence for durable [ReviewItem] rows (one per RawSms).
 */
interface ReviewRepository {
    suspend fun getById(id: String): ReviewItem?

    suspend fun findByRawSmsId(rawSmsId: String): ReviewItem?

    suspend fun listRequired(): List<ReviewItem>

    suspend fun listIgnored(): List<ReviewItem>

    suspend fun listAll(): List<ReviewItem>

    /**
     * RawSms ids whose REQUIRED review is only `processing_error`, oldest receipt first.
     * A resolved user review is not included.
     */
    suspend fun listRetryableProcessingErrorRawSmsIds(): List<String> = emptyList()

    /**
     * Create or refresh a REQUIRED review for [rawSmsId].
     * Preserves [ReviewItem.createdAt] when the row already exists.
     */
    suspend fun upsertRequired(
        rawSmsId: String,
        kind: ReviewKind,
        reasons: List<String>,
        now: Instant,
    ): ReviewItem

    suspend fun markResolved(
        id: String,
        resolutionKind: ReviewResolutionKind,
        resolvedAt: Instant,
        resolvedTransactionId: String?,
    ): ReviewItem?
}
