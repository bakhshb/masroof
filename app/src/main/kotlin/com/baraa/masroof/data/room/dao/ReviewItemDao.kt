package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.baraa.masroof.data.room.entity.ReviewItemEntity

@Dao
interface ReviewItemDao {
    @Query("SELECT * FROM review_item WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ReviewItemEntity?

    @Query("SELECT * FROM review_item WHERE rawSmsId = :rawSmsId LIMIT 1")
    suspend fun findByRawSmsId(rawSmsId: String): ReviewItemEntity?

    @Query("SELECT * FROM review_item WHERE status = :status ORDER BY createdAtEpochMillis, id")
    suspend fun listByStatus(status: String): List<ReviewItemEntity>

    @Query("SELECT * FROM review_item ORDER BY createdAtEpochMillis, id")
    suspend fun listAll(): List<ReviewItemEntity>

    @Query(
        """
        SELECT * FROM review_item
        WHERE status = 'RESOLVED' AND resolutionKind = 'USER_NON_FINANCIAL'
        ORDER BY resolvedAtEpochMillis DESC, id
        """,
    )
    suspend fun listIgnored(): List<ReviewItemEntity>

    /**
     * REQUIRED rows whose only reason is [reason], oldest RawSms receipt first.
     * RESOLVED rows are excluded, including an explicit user resolution.
     */
    @Query(
        """
        SELECT v.rawSmsId FROM review_item v
        INNER JOIN raw_sms r ON r.id = v.rawSmsId
        WHERE v.status = 'REQUIRED'
          AND v.resolutionKind IS NULL
          AND v.reasons = :reason
        ORDER BY r.receivedAtEpochMillis ASC, r.id ASC
        """,
    )
    suspend fun listRawSmsIdsForRequiredReason(reason: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: ReviewItemEntity): Long

    @Query(
        """
        UPDATE review_item SET
          kind = :kind,
          status = :status,
          reasons = :reasons,
          updatedAtEpochMillis = :updatedAtEpochMillis,
          resolvedAtEpochMillis = :resolvedAtEpochMillis,
          resolutionKind = :resolutionKind,
          resolvedTransactionId = :resolvedTransactionId
        WHERE id = :id
        """,
    )
    suspend fun updateRow(
        id: String,
        kind: String,
        status: String,
        reasons: String,
        updatedAtEpochMillis: Long,
        resolvedAtEpochMillis: Long?,
        resolutionKind: String?,
        resolvedTransactionId: String?,
    ): Int

    @Transaction
    suspend fun upsertRequiredAtomic(entity: ReviewItemEntity): ReviewItemEntity {
        val inserted = insertIfAbsent(entity)
        if (inserted != -1L) {
            return entity
        }
        val existing = findByRawSmsId(entity.rawSmsId)
            ?: getById(entity.id)
            ?: return entity

        // Durable history: never reopen RESOLVED rows from queue refresh.
        if (existing.status == "RESOLVED") {
            return existing
        }

        if (existing.status == entity.status &&
            existing.kind == entity.kind &&
            existing.reasons == entity.reasons
        ) {
            return existing
        }
        updateRow(
            id = existing.id,
            kind = entity.kind,
            status = entity.status,
            reasons = entity.reasons,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
            resolvedAtEpochMillis = existing.resolvedAtEpochMillis,
            resolutionKind = existing.resolutionKind,
            resolvedTransactionId = existing.resolvedTransactionId,
        )
        return existing.copy(
            kind = entity.kind,
            status = entity.status,
            reasons = entity.reasons,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
        )
    }

    /**
     * Resolve only when the row is still REQUIRED. Returns rows updated (0 or 1).
     */
    @Query(
        """
        UPDATE review_item SET
          status = :status,
          updatedAtEpochMillis = :updatedAtEpochMillis,
          resolvedAtEpochMillis = :resolvedAtEpochMillis,
          resolutionKind = :resolutionKind,
          resolvedTransactionId = :resolvedTransactionId
        WHERE id = :id AND status = 'REQUIRED'
        """,
    )
    suspend fun resolveIfRequired(
        id: String,
        status: String,
        resolutionKind: String,
        resolvedAtEpochMillis: Long,
        resolvedTransactionId: String?,
        updatedAtEpochMillis: Long,
    ): Int

    @Transaction
    suspend fun markResolvedAtomic(
        id: String,
        status: String,
        resolutionKind: String,
        resolvedAtEpochMillis: Long,
        resolvedTransactionId: String?,
        updatedAtEpochMillis: Long,
    ): ReviewItemEntity? {
        val existing = getById(id) ?: return null
        if (existing.status == "RESOLVED") {
            // Explicit ignore overrides any prior settlement metadata.
            if (resolutionKind == "USER_NON_FINANCIAL" &&
                (existing.resolutionKind != resolutionKind ||
                    existing.resolvedTransactionId != resolvedTransactionId)
            ) {
                updateRow(
                    id = id,
                    kind = existing.kind,
                    status = status,
                    reasons = existing.reasons,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                    resolvedAtEpochMillis = resolvedAtEpochMillis,
                    resolutionKind = resolutionKind,
                    resolvedTransactionId = resolvedTransactionId,
                )
                return existing.copy(
                    status = status,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                    resolvedAtEpochMillis = resolvedAtEpochMillis,
                    resolutionKind = resolutionKind,
                    resolvedTransactionId = resolvedTransactionId,
                )
            }
            // Allow upgrading auto-settlement metadata to an explicit USER_* kind.
            if (existing.resolutionKind == "AUTO_NO_LONGER_REQUIRED" &&
                resolutionKind != "AUTO_NO_LONGER_REQUIRED"
            ) {
                updateRow(
                    id = id,
                    kind = existing.kind,
                    status = status,
                    reasons = existing.reasons,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                    resolvedAtEpochMillis = resolvedAtEpochMillis,
                    resolutionKind = resolutionKind,
                    resolvedTransactionId = resolvedTransactionId ?: existing.resolvedTransactionId,
                )
                return existing.copy(
                    status = status,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                    resolvedAtEpochMillis = resolvedAtEpochMillis,
                    resolutionKind = resolutionKind,
                    resolvedTransactionId = resolvedTransactionId ?: existing.resolvedTransactionId,
                )
            }
            // Restoring a user-ignored message clears the durable ignore marker.
            if (existing.resolutionKind == "USER_NON_FINANCIAL" &&
                resolutionKind != "USER_NON_FINANCIAL"
            ) {
                updateRow(
                    id = id,
                    kind = existing.kind,
                    status = status,
                    reasons = existing.reasons,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                    resolvedAtEpochMillis = resolvedAtEpochMillis,
                    resolutionKind = resolutionKind,
                    resolvedTransactionId = resolvedTransactionId,
                )
                return existing.copy(
                    status = status,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                    resolvedAtEpochMillis = resolvedAtEpochMillis,
                    resolutionKind = resolutionKind,
                    resolvedTransactionId = resolvedTransactionId,
                )
            }
            return existing
        }
        val updated = resolveIfRequired(
            id = id,
            status = status,
            resolutionKind = resolutionKind,
            resolvedAtEpochMillis = resolvedAtEpochMillis,
            resolvedTransactionId = resolvedTransactionId,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
        if (updated == 0) return null
        return existing.copy(
            status = status,
            updatedAtEpochMillis = updatedAtEpochMillis,
            resolvedAtEpochMillis = resolvedAtEpochMillis,
            resolutionKind = resolutionKind,
            resolvedTransactionId = resolvedTransactionId,
        )
    }
}
