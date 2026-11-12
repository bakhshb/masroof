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
}
