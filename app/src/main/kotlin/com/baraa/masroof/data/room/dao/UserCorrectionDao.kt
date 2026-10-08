package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.baraa.masroof.data.room.entity.UserCorrectionEntity

@Dao
interface UserCorrectionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: UserCorrectionEntity)

    @Query(
        """
        SELECT * FROM user_correction
        WHERE targetRawSmsId = :rawSmsId
        ORDER BY createdAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun latestForRawSmsId(rawSmsId: String): UserCorrectionEntity?

    @Query(
        """
        SELECT * FROM user_correction
        WHERE targetRawSmsId = :rawSmsId
        ORDER BY createdAtEpochMillis ASC, id ASC
        """,
    )
    suspend fun listForRawSmsId(rawSmsId: String): List<UserCorrectionEntity>

    /** Callers keep [rawSmsIds] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query(
        """
        SELECT * FROM user_correction
        WHERE targetRawSmsId IN (:rawSmsIds)
        ORDER BY targetRawSmsId ASC, createdAtEpochMillis ASC, id ASC
        """,
    )
    suspend fun listForRawSmsIds(rawSmsIds: List<String>): List<UserCorrectionEntity>
}
