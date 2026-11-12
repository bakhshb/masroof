package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import com.baraa.masroof.domain.model.UserDecisionKind

/** Append-only: insert and read, never update or delete. */
@Dao
interface UserDecisionDao {
    /** The only way in: a `RECURRING` row names a series and no raw SMS; every other row names a raw SMS and no series. */
    suspend fun insert(entity: UserDecisionEntity): Long {
        val recurring = entity.kind == UserDecisionKind.RECURRING.name
        require(recurring == (entity.rawSmsId == null)) { "A decision names a raw SMS exactly when it is not a recurring answer" }
        require(if (recurring) entity.subjectKey != null && entity.answer != null
            else entity.subjectKey == null && entity.answer == null && entity.payload == null) { "Recurring columns on the wrong kind" }
        return insertChecked(entity)
    }

    /** Only through [insert]. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChecked(entity: UserDecisionEntity): Long

    @Query("SELECT * FROM user_decision ORDER BY sequence ASC")
    suspend fun listAll(): List<UserDecisionEntity>

    /** Rows naming any of [rawSmsIds] as subject or paired leg. Callers keep 2 × [rawSmsIds] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query(
        "SELECT * FROM user_decision WHERE rawSmsId IN (:rawSmsIds) OR pairedRawSmsId IN (:rawSmsIds) ORDER BY sequence ASC",
    )
    suspend fun listNaming(rawSmsIds: List<String>): List<UserDecisionEntity>

    /** Every recurring answer, by series and then in append order. */
    @Query("SELECT * FROM user_decision WHERE kind = 'RECURRING' ORDER BY subjectKey, sequence")
    suspend fun listRecurring(): List<UserDecisionEntity>
}

/**
 * SQL for "[column]'s latest outcome decision posts nothing" (`EffectiveUserDecisions.isNonFinancial`), for queries
 * that filter in SQL. Annotation arguments must be constants, so each query spells it with its own column.
 */
internal const val LATEST_OUTCOME_KIND_SQL =
    "SELECT d.kind FROM user_decision d WHERE d.kind NOT IN ('CORRECTION', 'BANK_SELECTION', 'RECURRING') AND "
internal const val NON_FINANCIAL_KINDS_SQL = "('NON_FINANCIAL', 'IGNORE', 'RESTORE_REVERTED')"

/** Closes a `COALESCE((LATEST_OUTCOME_KIND_SQL (row names X)` clause: "X's latest outcome is not non-financial". */
internal const val NOT_NON_FINANCIAL_SQL = " ORDER BY d.sequence DESC LIMIT 1), '') NOT IN " + NON_FINANCIAL_KINDS_SQL
