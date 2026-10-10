package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.dao.ReviewItemDao
import com.baraa.masroof.data.room.mapper.ReviewItemMapper
import com.baraa.masroof.domain.ids.ReviewIdFactory
import com.baraa.masroof.domain.model.ExplicitBankSelection
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.ReviewRepository
import java.time.Instant

class RoomReviewRepository(
    private val dao: ReviewItemDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : ReviewRepository {
    override suspend fun getById(id: String): ReviewItem? = accessGate.withAccess {
        dao.getById(id)?.let(ReviewItemMapper::toDomain)
    }

    override suspend fun findByRawSmsId(rawSmsId: String): ReviewItem? = accessGate.withAccess {
        dao.findByRawSmsId(rawSmsId)?.let(ReviewItemMapper::toDomain)
    }

    override suspend fun listRequired(): List<ReviewItem> = accessGate.withAccess {
        dao.listByStatus(ReviewStatus.REQUIRED.name).map(ReviewItemMapper::toDomain)
    }

    override suspend fun listIgnored(): List<ReviewItem> = accessGate.withAccess {
        dao.listIgnored().map(ReviewItemMapper::toDomain)
    }

    override suspend fun listAll(): List<ReviewItem> = accessGate.withAccess {
        dao.listAll().map(ReviewItemMapper::toDomain)
    }

    override suspend fun listRetryableProcessingErrorRawSmsIds(): List<String> = accessGate.withAccess {
        dao.listRawSmsIdsForRequiredReason(RETRYABLE_PROCESSING_ERROR_REASON)
    }

    override suspend fun upsertRequired(
        rawSmsId: String,
        kind: ReviewKind,
        reasons: List<String>,
        now: Instant,
    ): ReviewItem = accessGate.withAccess {
        val id = ReviewIdFactory.fromRawSmsId(rawSmsId)
        val existingReasons = findByRawSmsId(rawSmsId)?.reasons.orEmpty()
        val sortedReasons = ExplicitBankSelection.mergePreservedSelection(existingReasons, reasons)
        val entity = ReviewItemMapper.toEntity(
            ReviewItem(
                id = id,
                rawSmsId = rawSmsId,
                kind = kind,
                status = ReviewStatus.REQUIRED,
                reasons = sortedReasons,
                createdAt = now,
                updatedAt = now,
                resolvedAt = null,
                resolutionKind = null,
                resolvedTransactionId = null,
            ),
        )
        return@withAccess ReviewItemMapper.toDomain(dao.upsertRequiredAtomic(entity))
    }

    override suspend fun markResolved(
        id: String,
        resolutionKind: ReviewResolutionKind,
        resolvedAt: Instant,
        resolvedTransactionId: String?,
    ): ReviewItem? = accessGate.withAccess {
        val updated = dao.markResolvedAtomic(
            id = id,
            status = ReviewStatus.RESOLVED.name,
            resolutionKind = resolutionKind.name,
            resolvedAtEpochMillis = resolvedAt.toEpochMilli(),
            resolvedTransactionId = resolvedTransactionId,
            updatedAtEpochMillis = resolvedAt.toEpochMilli(),
        )
        return@withAccess updated?.let(ReviewItemMapper::toDomain)
    }

    private companion object {
        /** Persisted ingestion reason code for exhausted processing. */
        const val RETRYABLE_PROCESSING_ERROR_REASON = "processing_error"
    }
}
