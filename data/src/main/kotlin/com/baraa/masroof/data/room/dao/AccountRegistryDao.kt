package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.baraa.masroof.data.room.entity.AccountRegistryEntity

@Dao
interface AccountRegistryDao {
    /** Atomic create-if-absent. Returns -1 when the composite key already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: AccountRegistryEntity): Long

    /**
     * Observation metadata only — never touches [AccountRegistryEntity.ownershipStatus].
     * Preserves existing [firstSeenRawSmsId] when already set.
     */
    @Query(
        """
        UPDATE account_registry
        SET lastSeenRawSmsId = :rawSmsId,
            firstSeenRawSmsId = COALESCE(firstSeenRawSmsId, :rawSmsId)
        WHERE bankId = :bankId AND maskedNumber = :maskedNumber
        """,
    )
    suspend fun touchObservation(bankId: String, maskedNumber: String, rawSmsId: String): Int

    @Query(
        """
        UPDATE account_registry
        SET ownershipStatus = :ownershipStatus
        WHERE bankId = :bankId AND maskedNumber = :maskedNumber
        """,
    )
    suspend fun updateOwnership(
        bankId: String,
        maskedNumber: String,
        ownershipStatus: String,
    ): Int

    /**
     * API-26-safe ownership write: IGNORE-insert then explicit UPDATE in one transaction.
     * Never uses SQLite UPSERT (`ON CONFLICT ... DO UPDATE`).
     */
    @Transaction
    suspend fun setOwnershipAtomic(
        entity: AccountRegistryEntity,
        ownershipStatus: String,
    ) {
        insertIfAbsent(entity)
        updateOwnership(
            bankId = entity.bankId,
            maskedNumber = entity.maskedNumber,
            ownershipStatus = ownershipStatus,
        )
    }

    /**
     * Observation create-if-absent + metadata touch in one transaction.
     * Does not modify ownershipStatus when the row already exists.
     */
    @Transaction
    suspend fun observeAtomic(
        entity: AccountRegistryEntity,
        rawSmsId: String,
    ) {
        insertIfAbsent(entity)
        touchObservation(entity.bankId, entity.maskedNumber, rawSmsId)
    }

    @Query(
        """
        SELECT * FROM account_registry
        WHERE bankId = :bankId AND maskedNumber = :maskedNumber
        LIMIT 1
        """,
    )
    suspend fun get(bankId: String, maskedNumber: String): AccountRegistryEntity?

    @Query("SELECT * FROM account_registry ORDER BY bankId, maskedNumber")
    suspend fun listAll(): List<AccountRegistryEntity>

    @Query(
        """
        UPDATE account_registry
        SET displayName = :displayName
        WHERE bankId = :bankId AND maskedNumber = :maskedNumber
        """,
    )
    suspend fun updateDisplayName(
        bankId: String,
        maskedNumber: String,
        displayName: String?,
    ): Int

    @Query(
        """
        UPDATE account_registry
        SET accountType = :accountType
        WHERE bankId = :bankId AND maskedNumber = :maskedNumber
        """,
    )
    suspend fun updateAccountType(
        bankId: String,
        maskedNumber: String,
        accountType: String,
    ): Int

    @Query("DELETE FROM account_registry WHERE bankId = :bankId AND maskedNumber = :maskedNumber")
    suspend fun delete(bankId: String, maskedNumber: String): Int
}
