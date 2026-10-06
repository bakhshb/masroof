package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.baraa.masroof.data.room.entity.ProcessingRetryEntity

@Dao
interface ProcessingRetryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProcessingRetryEntity)

    @Query("DELETE FROM processing_retry WHERE rawSmsId = :rawSmsId")
    suspend fun delete(rawSmsId: String)

    @Query("DELETE FROM processing_retry WHERE rawSmsId IN (:rawSmsIds)")
    suspend fun deleteAll(rawSmsIds: List<String>)

    /**
     * One transaction for the whole set. A failure rolls every row in [entities] back.
     */
    @Transaction
    suspend fun upsertAllAtomic(entities: List<ProcessingRetryEntity>) {
        for (entity in entities) {
            upsert(entity)
        }
    }

    /** One transaction. Chunks stay inside the transaction so a failure rolls the clear back. */
    @Transaction
    suspend fun deleteAllAtomic(rawSmsIds: List<String>) {
        for (chunk in rawSmsIds.distinct().chunked(RoomBatch.MAX_BIND_ARGS)) {
            deleteAll(chunk)
        }
    }

    /**
     * Oldest receipt first. A [com.baraa.masroof.domain.model.ReviewResolutionKind.USER_NON_FINANCIAL]
     * review stays closed and is not returned.
     */
    @Query(
        """
        SELECT p.rawSmsId FROM processing_retry p
        INNER JOIN raw_sms r ON r.id = p.rawSmsId
        LEFT JOIN review_item v ON v.rawSmsId = p.rawSmsId
        WHERE v.resolutionKind IS NULL OR v.resolutionKind != 'USER_NON_FINANCIAL'
        ORDER BY r.receivedAtEpochMillis ASC, r.id ASC
        """,
    )
    suspend fun listRetryableRawSmsIds(): List<String>

    /**
     * Retry rows of one [mode]. A review row does not change this set.
     * [com.baraa.masroof.domain.model.ReviewResolutionKind.USER_NON_FINANCIAL] stays excluded.
     */
    @Query(
        """
        SELECT p.rawSmsId FROM processing_retry p
        INNER JOIN raw_sms r ON r.id = p.rawSmsId
        LEFT JOIN review_item v ON v.rawSmsId = p.rawSmsId
        WHERE p.mode = :mode
          AND (v.resolutionKind IS NULL OR v.resolutionKind != 'USER_NON_FINANCIAL')
        ORDER BY r.receivedAtEpochMillis ASC, r.id ASC
        """,
    )
    suspend fun listRetryableRawSmsIdsByMode(mode: String): List<String>
}
