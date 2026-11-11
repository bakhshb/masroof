package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.RawSms
import java.time.Instant

/**
 * Domain-facing RawSms persistence. Implementations live in the data layer.
 *
 * Duplicate-aware insertion supports safe ingestion without creating
 * duplicate evidence rows.
 */
interface RawSmsRepository {
    suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult

    suspend fun getById(id: String): RawSms?

    /** Batch lookup; missing ids are skipped. Result order is unspecified. */
    suspend fun getByIds(ids: Collection<String>): List<RawSms> =
        ids.distinct().mapNotNull { getById(it) }

    /** Receipt time of each stored id in [ids], without loading SMS bodies. */
    suspend fun receivedAtByIds(ids: Collection<String>): Map<String, java.time.Instant> =
        getByIds(ids).associate { it.id to it.receivedAt }

    suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms?

    /**
     * Resolves a provider id stored on a RawSms row or only as a live↔inbox alias.
     * The alias does not change the original row.
     */
    suspend fun findByProviderMessageId(providerMessageId: String): RawSms? =
        findByDeviceMessageId(providerMessageId)

    /** Subset of [rawSmsIds] that already have a provider alias. */
    suspend fun providerAliasRawSmsIds(rawSmsIds: Collection<String>): Set<String> = emptySet()

    /**
     * Records that [providerMessageId] names the already stored live row [rawSmsId].
     * Does not update that row. Returns false when the provider id is already taken.
     */
    suspend fun rememberProviderAlias(providerMessageId: String, rawSmsId: String): Boolean = false

    /**
     * Every stored RawSms id, oldest receipt first. Used by bulk reprocessing so
     * evidence without a ParsedEvent (Unsupported / Invalid / failed) is retried.
     */
    suspend fun listIdsByReceivedAt(): List<String>

    /**
     * Stored RawSms ids with no durable processing outcome yet (neither a ParsedEvent nor
     * a review row), oldest receipt first. Captured evidence whose processing was lost to
     * process death is found here and rescheduled.
     */
    suspend fun listIdsAwaitingProcessing(): List<String> = emptyList()

    /** Receipt time of the newest stored SMS not after [notAfter], or null when there is none. */
    suspend fun newestReceivedAt(notAfter: java.time.Instant): java.time.Instant? = null

    /**
     * Live↔historical near-duplicates: same sender + bodyHash within
     * [fromInclusive]…[toInclusive], opposite deviceMessageId nullness; up to two rows
     * so capture can tell a unique twin from an ambiguous pair. Ordered by receipt
     * time, then id. A live-row lookup skips rows that already carry a provider alias.
     *
     * @param lookingForLiveRow when true, match rows with null deviceMessageId
     * (incoming is historical). When false, match rows with non-null
     * deviceMessageId (incoming is live).
     */
    suspend fun listCrossSourceNearDuplicates(
        sender: String,
        bodyHash: String,
        fromInclusive: Instant,
        toInclusive: Instant,
        lookingForLiveRow: Boolean,
    ): List<RawSms>
}

/**
 * Explicit outcome for expected duplicate detection (not exceptional).
 */
sealed interface RawSmsInsertResult {
    data object Inserted : RawSmsInsertResult

    data object AlreadyExists : RawSmsInsertResult
}
