package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.dao.AccountRegistryDao
import com.baraa.masroof.data.room.entity.AccountRegistryEntity
import com.baraa.masroof.data.room.mapper.RegistryMapper
import com.baraa.masroof.domain.ids.RegistryEntityIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.ownership.RegistryIdentity
import com.baraa.masroof.domain.repository.AccountRegistryRepository

class RoomAccountRegistryRepository(
    private val dao: AccountRegistryDao,
    private val bankRegistryDao: com.baraa.masroof.data.room.dao.BankRegistryDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : AccountRegistryRepository {
    override suspend fun observe(reference: AccountReference, rawSmsId: String): Unit = accessGate.withAccess<Unit> {
        if (!RegistryIdentity.isKnownBank(reference.bank)) return@withAccess
        val masked = reference.maskedNumber?.trim().orEmpty()
        if (masked.isEmpty()) return@withAccess

        bankRegistryDao.insertIfAbsent(
            com.baraa.masroof.data.room.entity.BankRegistryEntity(bankId = reference.bank.id),
        )

        // IGNORE-insert as UNKNOWN; never overwrites ownership on conflict.
        dao.observeAtomic(
            entity = AccountRegistryEntity(
                id = RegistryEntityIdFactory.newAccountId(),
                bankId = reference.bank.id,
                maskedNumber = masked,
                ownershipStatus = OwnershipStatus.UNKNOWN.name,
                accountType = com.baraa.masroof.domain.model.AccountType.CURRENT.name,
                firstSeenRawSmsId = rawSmsId,
                lastSeenRawSmsId = rawSmsId,
            ),
            rawSmsId = rawSmsId,
        )
    }

    override suspend fun setOwnership(reference: AccountReference, status: OwnershipStatus): Unit = accessGate.withAccess<Unit> {
        RegistryIdentity.requireKnownBank(reference.bank, "AccountRegistry.setOwnership")
        val masked = reference.maskedNumber?.trim().orEmpty()
        require(masked.isNotEmpty()) { "maskedNumber required to set ownership" }

        // Confirmation-before-observation may create a row with null seen metadata.
        dao.setOwnershipAtomic(
            entity = AccountRegistryEntity(
                id = RegistryEntityIdFactory.newAccountId(),
                bankId = reference.bank.id,
                maskedNumber = masked,
                ownershipStatus = status.name,
                firstSeenRawSmsId = null,
                lastSeenRawSmsId = null,
            ),
            ownershipStatus = status.name,
        )
    }

    override suspend fun resolve(reference: AccountReference): OwnershipStatus = accessGate.withAccess {
        val entry = findRegistryEntry(reference) ?: return@withAccess OwnershipStatus.UNKNOWN
        return@withAccess OwnershipStatus.valueOf(entry.ownershipStatus)
    }

    override suspend fun get(reference: AccountReference): AccountRegistryEntry? = accessGate.withAccess {
        return@withAccess findRegistryEntry(reference)?.let(RegistryMapper::toAccountEntry)
    }

    override suspend fun listAll(): List<AccountRegistryEntry> = accessGate.withAccess {
        dao.listAll().map(RegistryMapper::toAccountEntry)
    }

    override suspend fun updateDisplayName(reference: AccountReference, displayName: String?): Unit = accessGate.withAccess<Unit> {
        RegistryIdentity.requireKnownBank(reference.bank, "AccountRegistry.updateDisplayName")
        val masked = reference.maskedNumber?.trim().orEmpty()
        require(masked.isNotEmpty()) { "maskedNumber required" }
        dao.updateDisplayName(reference.bank.id, masked, displayName?.trim()?.ifEmpty { null })
    }

    override suspend fun updateAccountType(reference: AccountReference, accountType: com.baraa.masroof.domain.model.AccountType): Unit = accessGate.withAccess<Unit> {
        RegistryIdentity.requireKnownBank(reference.bank, "AccountRegistry.updateAccountType")
        val masked = reference.maskedNumber?.trim().orEmpty()
        require(masked.isNotEmpty()) { "maskedNumber required" }
        val updated = dao.updateAccountType(reference.bank.id, masked, accountType.name)
        if (updated == 0) {
            throw IllegalArgumentException("account_not_found")
        }
    }

    /**
     * Exact composite-key lookup first. When the exact row is missing or still
     * [OwnershipStatus.UNKNOWN], fall back to a unique same-bank last-4 suffix match
     * so SMS refs like 3001 resolve owned registry rows stored as longer masks.
     */
    private suspend fun findRegistryEntry(reference: AccountReference): AccountRegistryEntity? {
        if (!RegistryIdentity.isKnownBank(reference.bank)) return null
        val masked = reference.maskedNumber?.trim().orEmpty()
        if (masked.isEmpty()) return null

        val exact = dao.get(reference.bank.id, masked)
        val suffixMatch = findUniqueSuffixMatch(reference.bank.id, masked)
        val ownedSuffixMatch = findUniqueOwnedSuffixMatch(reference.bank.id, masked)

        if (exact != null) {
            val exactStatus = OwnershipStatus.valueOf(exact.ownershipStatus)
            if (exactStatus != OwnershipStatus.UNKNOWN) return exact
            if (ownedSuffixMatch != null) return ownedSuffixMatch
            return exact
        }

        return suffixMatch
    }

    private suspend fun findUniqueSuffixMatch(
        bankId: String,
        masked: String,
    ): AccountRegistryEntity? = suffixMatches(bankId, masked).singleOrNull()

    private suspend fun findUniqueOwnedSuffixMatch(
        bankId: String,
        masked: String,
    ): AccountRegistryEntity? =
        suffixMatches(bankId, masked)
            .filter { OwnershipStatus.valueOf(it.ownershipStatus) == OwnershipStatus.OWNED }
            .singleOrNull()

    private suspend fun suffixMatches(
        bankId: String,
        masked: String,
    ): List<AccountRegistryEntity> {
        val suffix = masked.takeLast(4)
        if (suffix.length < 4) return emptyList()
        return dao.listAll().filter { entity ->
            entity.bankId == bankId &&
                entity.maskedNumber.trim().takeLast(4) == suffix
        }
    }

    companion object {
        fun from(database: MasroofDatabase, accessGate: DatabaseAccessGate = DatabaseAccessGate()): RoomAccountRegistryRepository =
            RoomAccountRegistryRepository(
                dao = database.accountRegistryDao(),
                bankRegistryDao = database.bankRegistryDao(),
                accessGate = accessGate,
            )
    }
}
