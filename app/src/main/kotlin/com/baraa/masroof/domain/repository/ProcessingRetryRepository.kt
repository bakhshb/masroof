package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.ProcessingRetryMode
import java.time.Instant

/**
 * Durable ids whose derived processing must run again.
 *
 * This is not a review decision. A resolved financial review can keep its
 * resolution while the id stays here. An SMS the user decided posts nothing
 * ([com.baraa.masroof.domain.model.EffectiveUserDecisions.isNonFinancial]) is excluded by [listRetryableRawSmsIds].
 */
interface ProcessingRetryRepository {
    suspend fun markRequired(rawSmsId: String, createdAt: Instant, mode: ProcessingRetryMode)

    /**
     * Inserts every id in one transaction with the same [mode].
     * A failure leaves none of [rawSmsIds] newly accepted.
     */
    suspend fun markRequired(rawSmsIds: List<String>, createdAt: Instant, mode: ProcessingRetryMode)

    /** Deletes every id in one transaction. */
    suspend fun clear(rawSmsIds: List<String>)

    /** Oldest RawSms receipt first. Excludes a non-financial resolution. */
    suspend fun listRetryableRawSmsIds(): List<String> = emptyList()

    /**
     * Retry rows of [mode], oldest receipt first.
     * A non-financial resolution is excluded. Review rows do not change the mode.
     */
    suspend fun listRetryableRawSmsIds(mode: ProcessingRetryMode): List<String> = emptyList()
}
