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

    @Query("SELECT * FROM user_correction ORDER BY targetRawSmsId ASC, createdAtEpochMillis ASC, id ASC")
    suspend fun listAll(): List<UserCorrectionEntity>
}
