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

    /** Batch lookup; missing ids are skipped. Result order is unspecified. */
    suspend fun getByIds(ids: Collection<String>): List<RawSms> =
        ids.distinct().mapNotNull { getById(it) }

    suspend fun existsById(id: String): Boolean

    suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms?

    /**
     * Resolves a provider id stored on a RawSms row or only as a live↔inbox alias.
     * The alias does not change the original row.
     */
    suspend fun findByProviderMessageId(providerMessageId: String): RawSms? =
        findByDeviceMessageId(providerMessageId)

    /** RawSms id already linked to this inbox provider id, if the link is an alias. */
    suspend fun findProviderAliasRawSmsId(providerMessageId: String): String? = null

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

    /**
     * Same match as [findCrossSourceNearDuplicate], up to two rows so capture can
     * tell a unique twin from an ambiguous pair. Ordered by receipt time.
     */
    suspend fun listCrossSourceNearDuplicates(
        sender: String,
        bodyHash: String,
        fromInclusive: Instant,
        toInclusive: Instant,
        lookingForLiveRow: Boolean,
    ): List<RawSms> = listOfNotNull(
        findCrossSourceNearDuplicate(
            sender = sender,
            bodyHash = bodyHash,
            fromInclusive = fromInclusive,
            toInclusive = toInclusive,
            lookingForLiveRow = lookingForLiveRow,
        ),
    )
}

/**
 * Explicit outcome for expected duplicate detection (not exceptional).
 */
sealed interface RawSmsInsertResult {
    data object Inserted : RawSmsInsertResult

    data object AlreadyExists : RawSmsInsertResult
}
