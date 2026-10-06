package com.baraa.masroof.domain.repository

import java.time.Instant

/**
 * Durable ids whose derived processing must run again.
 *
 * This is not a review decision. A resolved financial review can keep its
 * resolution while the id stays here. [com.baraa.masroof.domain.model.ReviewResolutionKind.USER_NON_FINANCIAL]
 * is excluded by [listRetryableRawSmsIds].
 */
interface ProcessingRetryRepository {
    suspend fun markRequired(rawSmsId: String, createdAt: Instant)

    /**
     * Inserts every id in one transaction. A failure leaves none of [rawSmsIds] newly accepted.
     */
    suspend fun markRequired(rawSmsIds: List<String>, createdAt: Instant)

    suspend fun clear(rawSmsId: String)

    /** Deletes every id in one transaction. */
    suspend fun clear(rawSmsIds: List<String>)

    /** Oldest RawSms receipt first. Excludes a non-financial resolution. */
    suspend fun listRetryableRawSmsIds(): List<String> = emptyList()

    /** Retry rows that already have a review. Live startup schedules these per message. */
    suspend fun listReviewedRetryableRawSmsIds(): List<String> = emptyList()

    /** Retry rows with no review. Historical recovery processes these as one batch. */
    suspend fun listUnreviewedRetryableRawSmsIds(): List<String> = emptyList()
}
