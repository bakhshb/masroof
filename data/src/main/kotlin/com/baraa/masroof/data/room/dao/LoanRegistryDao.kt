package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.baraa.masroof.data.room.entity.LoanRegistryEntity

@Dao
interface LoanRegistryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: LoanRegistryEntity): Long

    @Query(
        """
        UPDATE loan_registry
        SET lastSeenRawSmsId = :rawSmsId,
            firstSeenRawSmsId = COALESCE(firstSeenRawSmsId, :rawSmsId)
        WHERE bankId = :bankId AND loanType = :loanType
        """,
    )
    suspend fun touchObservation(bankId: String, loanType: String, rawSmsId: String): Int

    @Query(
        """
        UPDATE loan_registry
        SET ownershipStatus = :ownershipStatus
        WHERE bankId = :bankId AND loanType = :loanType
        """,
    )
    suspend fun updateOwnership(
        bankId: String,
        loanType: String,
        ownershipStatus: String,
    ): Int

    @Transaction
    suspend fun setOwnershipAtomic(
        entity: LoanRegistryEntity,
        ownershipStatus: String,
    ) {
        insertIfAbsent(entity)
        updateOwnership(
            bankId = entity.bankId,
            loanType = entity.loanType,
            ownershipStatus = ownershipStatus,
        )
    }

    @Transaction
    suspend fun observeAtomic(
        entity: LoanRegistryEntity,
        rawSmsId: String,
    ) {
        insertIfAbsent(entity)
        touchObservation(entity.bankId, entity.loanType, rawSmsId)
    }

    @Query(
        """
        SELECT * FROM loan_registry
        WHERE bankId = :bankId AND loanType = :loanType
        LIMIT 1
        """,
    )
    suspend fun get(bankId: String, loanType: String): LoanRegistryEntity?

    @Query("SELECT * FROM loan_registry ORDER BY bankId, loanType")
    suspend fun listAll(): List<LoanRegistryEntity>

    @Query(
        """
        UPDATE loan_registry
        SET displayName = :displayName
        WHERE bankId = :bankId AND loanType = :loanType
        """,
    )
    suspend fun updateDisplayName(
        bankId: String,
        loanType: String,
        displayName: String?,
    ): Int

    @Query("DELETE FROM loan_registry WHERE bankId = :bankId AND loanType = :loanType")
    suspend fun delete(bankId: String, loanType: String): Int
}
