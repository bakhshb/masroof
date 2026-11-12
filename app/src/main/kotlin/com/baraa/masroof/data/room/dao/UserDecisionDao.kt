package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.baraa.masroof.data.room.entity.UserDecisionEntity

/** Append-only: insert and read, never update or delete. */
@Dao
interface UserDecisionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: UserDecisionEntity): Long

    @Query("SELECT * FROM user_decision ORDER BY sequence ASC")
    suspend fun listAll(): List<UserDecisionEntity>

    /** Rows naming any of [rawSmsIds] as subject or paired leg. Callers keep 2 × [rawSmsIds] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query(
        "SELECT * FROM user_decision WHERE rawSmsId IN (:rawSmsIds) OR pairedRawSmsId IN (:rawSmsIds) ORDER BY sequence ASC",
    )
    suspend fun listNaming(rawSmsIds: List<String>): List<UserDecisionEntity>
}

/**
 * SQL for "[column]'s latest outcome decision posts nothing" (`EffectiveUserDecisions.isNonFinancial`), for queries
 * that filter in SQL. Annotation arguments must be constants, so each query spells it with its own column.
 */
internal const val LATEST_OUTCOME_KIND_SQL =
    "SELECT d.kind FROM user_decision d WHERE d.kind NOT IN ('CORRECTION', 'BANK_SELECTION') AND "
internal const val NON_FINANCIAL_KINDS_SQL = "('NON_FINANCIAL', 'IGNORE', 'RESTORE_REVERTED')"
