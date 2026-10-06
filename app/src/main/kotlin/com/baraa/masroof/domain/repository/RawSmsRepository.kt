package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.RawSms
import java.time.Instant

/**
 * Domain-facing RawSms persistence. Implementations live in the data layer.
 *
 * Duplicate-aware insertion supports safe P6 ingestion without creating
 * duplicate evidence rows.
 */
interface RawSmsRepository {
    suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult

    suspend fun getById(id: String): RawSms?

    suspend fun existsById(id: String): Boolean

    suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms?

    /**
     * Every stored RawSms id, oldest receipt first. Used by bulk reprocessing so
     * evidence without a ParsedEvent (Unsupported / Invalid / failed) is retried.
     */
    suspend fun listIdsByReceivedAt(): List<String>

    /**
     * Live↔historical near-duplicate: same sender + bodyHash within
     * [fromInclusive]…[toInclusive], opposite deviceMessageId nullness.
     *
     * @param lookingForLiveRow when true, match rows with null deviceMessageId
     * (incoming is historical). When false, match rows with non-null
     * deviceMessageId (incoming is live).
     */
    suspend fun findCrossSourceNearDuplicate(
        sender: String,
        bodyHash: String,
        fromInclusive: Instant,
        toInclusive: Instant,
        lookingForLiveRow: Boolean,
    ): RawSms?
}

/**
 * Explicit outcome for expected duplicate detection (not exceptional).
 */
sealed interface RawSmsInsertResult {
    data object Inserted : RawSmsInsertResult

    data object AlreadyExists : RawSmsInsertResult
}
