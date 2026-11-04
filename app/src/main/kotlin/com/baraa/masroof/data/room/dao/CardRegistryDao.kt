package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.baraa.masroof.data.room.entity.CardRegistryEntity
import com.baraa.masroof.domain.ids.RegistryEntityIdFactory

@Dao
interface CardRegistryDao {
    @Query("SELECT COUNT(*) FROM card_registry WHERE bankId = :bankId AND parentCardLast4 = :last4")
    suspend fun dependentCount(bankId: String, last4: String): Int

    /** All fields of this card change together; foreign card rows and ledger rows are untouched. */
    @Transaction
    suspend fun configureAtomic(bankId: String, last4: String, configuration: com.baraa.masroof.domain.model.CardConfiguration) {
        val existing = requireNotNull(get(bankId, last4)) { "card_not_found" }
        configuration.validate(com.baraa.masroof.domain.model.Bank(bankId), last4)
        require(existing.ownershipStatus == "OWNED") { "card_not_owned" }
        require(dependentCount(bankId, last4) == 0 ||
            (configuration.type.name == "CREDIT" && configuration.role?.name == "PRIMARY")) { "card_has_dependents" }
        configuration.linkedAccount?.let { account ->
            require(ownedAccountExists(bankId, requireNotNull(account.maskedNumber))) { "account_not_owned" }
        }
        val facilityId = if (configuration.type.name == "CREDIT") {
            val primary = configuration.primaryLast4 ?: last4
            val parentFacility = if (configuration.role?.name == "SUPPLEMENTARY") {
                val parent = requireNotNull(get(bankId, primary)) { "primary_not_found" }
                require(parent.cardRole == "PRIMARY" && parent.cardType == "CREDIT" && parent.ownershipStatus == "OWNED") { "invalid_primary" }
                parent.creditFacilityId
            } else null
            val retainedFacility = existing.creditFacilityId.takeIf {
                existing.cardType == "CREDIT" && existing.cardRole == configuration.role?.name &&
                    existing.parentCardLast4 == configuration.primaryLast4
            }
            (parentFacility ?: retainedFacility ?: RegistryEntityIdFactory.stableCreditFacilityId(bankId, primary)).also {
                insertFacilityIfAbsent(it, bankId, primary)
            }
        } else null
        updateCardNetwork(bankId, last4, configuration.network?.takeUnless { it.name == "UNKNOWN" }?.name)
        updateCardType(bankId, last4, configuration.type.name)
        updateLinkedAccount(bankId, last4, configuration.linkedAccount?.bank?.id, configuration.linkedAccount?.maskedNumber)
        updateCardRole(bankId, last4, configuration.role?.name, configuration.primaryLast4)
        updateCreditFacilityId(bankId, last4, facilityId)
    }

    @Query("SELECT EXISTS(SELECT 1 FROM account_registry WHERE bankId = :bankId AND maskedNumber = :masked AND ownershipStatus = 'OWNED')")
    suspend fun ownedAccountExists(bankId: String, masked: String): Boolean

    /** Atomic create-if-absent. Returns -1 when the composite key already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: CardRegistryEntity): Long

    /**
     * Observation metadata only — never touches [CardRegistryEntity.ownershipStatus].
     * Preserves existing [firstSeenRawSmsId] when already set.
     */
    @Query(
        """
        UPDATE card_registry
        SET lastSeenRawSmsId = :rawSmsId,
            firstSeenRawSmsId = COALESCE(firstSeenRawSmsId, :rawSmsId)
        WHERE bankId = :bankId AND last4 = :last4
        """,
    )
    suspend fun touchObservation(bankId: String, last4: String, rawSmsId: String): Int

    @Query(
        """
        UPDATE card_registry
        SET ownershipStatus = :ownershipStatus
        WHERE bankId = :bankId AND last4 = :last4
        """,
    )
    suspend fun updateOwnership(
        bankId: String,
        last4: String,
        ownershipStatus: String,
    ): Int

    /**
     * API-26-safe ownership write: IGNORE-insert then explicit UPDATE in one transaction.
     * Never uses SQLite UPSERT (`ON CONFLICT ... DO UPDATE`).
     */
    @Transaction
    suspend fun setOwnershipAtomic(
        entity: CardRegistryEntity,
        ownershipStatus: String,
    ) {
        insertIfAbsent(entity)
        updateOwnership(
            bankId = entity.bankId,
            last4 = entity.last4,
            ownershipStatus = ownershipStatus,
        )
    }

    /**
     * Observation create-if-absent + metadata touch in one transaction.
     * Does not modify ownershipStatus when the row already exists.
     */
    @Transaction
    suspend fun observeAtomic(
        entity: CardRegistryEntity,
        rawSmsId: String,
    ) {
        insertIfAbsent(entity)
        touchObservation(entity.bankId, entity.last4, rawSmsId)
    }

    @Query(
        """
        SELECT * FROM card_registry
        WHERE bankId = :bankId AND last4 = :last4
        LIMIT 1
        """,
    )
    suspend fun get(bankId: String, last4: String): CardRegistryEntity?

    @Query("SELECT * FROM card_registry ORDER BY bankId, last4")
    suspend fun listAll(): List<CardRegistryEntity>

    @Query(
        """
        UPDATE card_registry
        SET displayName = :displayName
        WHERE bankId = :bankId AND last4 = :last4
        """,
    )
    suspend fun updateDisplayName(bankId: String, last4: String, displayName: String?): Int

    @Query(
        """
        UPDATE card_registry
        SET cardNetwork = :cardNetwork
        WHERE bankId = :bankId AND last4 = :last4
        """,
    )
    suspend fun updateCardNetwork(bankId: String, last4: String, cardNetwork: String?): Int

    @Query(
        """
        UPDATE card_registry
        SET cardType = :cardType
        WHERE bankId = :bankId AND last4 = :last4
        """,
    )
    suspend fun updateCardType(bankId: String, last4: String, cardType: String?): Int

    @Query(
        """
        UPDATE card_registry
        SET linkedAccountBankId = :linkedAccountBankId,
            linkedAccountMaskedNumber = :linkedAccountMaskedNumber
        WHERE bankId = :bankId AND last4 = :last4
        """,
    )
    suspend fun updateLinkedAccount(
        bankId: String,
        last4: String,
        linkedAccountBankId: String?,
        linkedAccountMaskedNumber: String?,
    ): Int

    @Query(
        """
        UPDATE card_registry
        SET cardRole = :cardRole,
            parentCardLast4 = :parentCardLast4
        WHERE bankId = :bankId AND last4 = :last4
        """,
    )
    suspend fun updateCardRole(
        bankId: String,
        last4: String,
        cardRole: String?,
        parentCardLast4: String?,
    ): Int

    @Query(
        """
        INSERT OR IGNORE INTO credit_facility (id, bankId, primaryLast4, displayName)
        VALUES (:id, :bankId, :primaryLast4, NULL)
        """,
    )
    suspend fun insertFacilityIfAbsent(id: String, bankId: String, primaryLast4: String)

    @Query(
        """
        UPDATE card_registry
        SET creditFacilityId = :facilityId
        WHERE bankId = :bankId AND last4 = :last4
        """,
    )
    suspend fun updateCreditFacilityId(bankId: String, last4: String, facilityId: String?)

    @Query("DELETE FROM card_registry WHERE bankId = :bankId AND last4 = :last4")
    suspend fun deleteRow(bankId: String, last4: String): Int

    @Query(
        """
        DELETE FROM credit_facility
        WHERE id = :facilityId AND NOT EXISTS (SELECT 1 FROM card_registry WHERE creditFacilityId = :facilityId)
        """,
    )
    suspend fun deleteFacilityIfUnused(facilityId: String)

    /** Removes the card and its credit facility once no other card shares it. */
    @Transaction
    suspend fun deleteAtomic(bankId: String, last4: String): Int {
        val facility = get(bankId, last4)?.creditFacilityId
        val deleted = deleteRow(bankId, last4)
        facility?.let { deleteFacilityIfUnused(it) }
        return deleted
    }
}
