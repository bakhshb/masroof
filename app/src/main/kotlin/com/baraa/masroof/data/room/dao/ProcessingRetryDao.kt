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

    /** Oldest receipt first. An SMS whose latest outcome decision posts nothing stays closed and is not returned. */
    @Query(
        """
        SELECT p.rawSmsId FROM processing_retry p
        INNER JOIN raw_sms r ON r.id = p.rawSmsId
        WHERE $RETRY_NOT_DECIDED_NON_FINANCIAL
        ORDER BY r.receivedAtEpochMillis ASC, r.id ASC
        """,
    )
    suspend fun listRetryableRawSmsIds(): List<String>

    /** Retry rows of one [mode]. A review row does not change this set; a decided non-financial SMS stays excluded. */
    @Query(
        """
        SELECT p.rawSmsId FROM processing_retry p
        INNER JOIN raw_sms r ON r.id = p.rawSmsId
        WHERE p.mode = :mode
          AND $RETRY_NOT_DECIDED_NON_FINANCIAL
        ORDER BY r.receivedAtEpochMillis ASC, r.id ASC
        """,
    )
    suspend fun listRetryableRawSmsIdsByMode(mode: String): List<String>
}

/** `p.rawSmsId` is not an SMS whose latest outcome decision posts nothing (`EffectiveUserDecisions.isNonFinancial`). */
private const val RETRY_NOT_DECIDED_NON_FINANCIAL = "COALESCE((" + LATEST_OUTCOME_KIND_SQL +
    "(d.rawSmsId = p.rawSmsId OR d.pairedRawSmsId = p.rawSmsId)" + NOT_NON_FINANCIAL_SQL
