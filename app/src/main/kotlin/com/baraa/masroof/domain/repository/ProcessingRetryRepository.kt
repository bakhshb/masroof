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

    suspend fun clear(rawSmsId: String)

    /** Oldest RawSms receipt first. */
    suspend fun listRetryableRawSmsIds(): List<String> = emptyList()
}
