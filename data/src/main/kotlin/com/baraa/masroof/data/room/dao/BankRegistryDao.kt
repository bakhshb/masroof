package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.baraa.masroof.data.room.entity.BankRegistryEntity

@Dao
interface BankRegistryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: BankRegistryEntity): Long

    @Query("SELECT * FROM bank_registry WHERE bankId = :bankId LIMIT 1")
    suspend fun get(bankId: String): BankRegistryEntity?

    @Query("SELECT * FROM bank_registry ORDER BY bankId")
    suspend fun listAll(): List<BankRegistryEntity>

    @Query(
        """
        UPDATE bank_registry
        SET displayName = :displayName
        WHERE bankId = :bankId
        """,
    )
    suspend fun updateDisplayName(bankId: String, displayName: String?): Int
}
