package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.dao.LoanRegistryDao
import com.baraa.masroof.data.room.entity.LoanRegistryEntity
import com.baraa.masroof.domain.ids.RegistryEntityIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.ownership.RegistryIdentity
import com.baraa.masroof.domain.repository.LoanRegistryRepository

class RoomLoanRegistryRepository(
    private val dao: LoanRegistryDao,
    private val bankRegistryDao: com.baraa.masroof.data.room.dao.BankRegistryDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : LoanRegistryRepository {
    override suspend fun observe(reference: LoanReference, rawSmsId: String): Unit = accessGate.withAccess<Unit> {
        if (!RegistryIdentity.isKnownBank(reference.bank)) return@withAccess

        bankRegistryDao.insertIfAbsent(
            com.baraa.masroof.data.room.entity.BankRegistryEntity(bankId = reference.bank.id),
        )

        dao.observeAtomic(
            entity = LoanRegistryEntity(
                id = stableId(reference),
                bankId = reference.bank.id,
                loanType = reference.loanType.name,
                ownershipStatus = OwnershipStatus.UNKNOWN.name,
                firstSeenRawSmsId = rawSmsId,
                lastSeenRawSmsId = rawSmsId,
            ),
            rawSmsId = rawSmsId,
        )
    }

    override suspend fun setOwnership(reference: LoanReference, status: OwnershipStatus): Unit = accessGate.withAccess<Unit> {
        RegistryIdentity.requireKnownBank(reference.bank, "LoanRegistry.setOwnership")
        dao.setOwnershipAtomic(
            entity = LoanRegistryEntity(
                id = stableId(reference),
                bankId = reference.bank.id,
                loanType = reference.loanType.name,
                ownershipStatus = status.name,
                firstSeenRawSmsId = null,
                lastSeenRawSmsId = null,
            ),
            ownershipStatus = status.name,
        )
    }

    override suspend fun resolve(reference: LoanReference): OwnershipStatus = accessGate.withAccess {
        val entry = findRegistryEntry(reference) ?: return@withAccess OwnershipStatus.UNKNOWN
        return@withAccess OwnershipStatus.valueOf(entry.ownershipStatus)
    }

    override suspend fun get(reference: LoanReference): LoanRegistryEntry? = accessGate.withAccess {
        findRegistryEntry(reference)?.let(::toEntry)
    }

    override suspend fun listAll(): List<LoanRegistryEntry> = accessGate.withAccess {
        dao.listAll().map(::toEntry)
    }

    override suspend fun updateDisplayName(reference: LoanReference, displayName: String?): Unit = accessGate.withAccess<Unit> {
        RegistryIdentity.requireKnownBank(reference.bank, "LoanRegistry.updateDisplayName")
        dao.updateDisplayName(
            reference.bank.id,
            reference.loanType.name,
            displayName?.trim()?.ifEmpty { null },
        )
    }

    private fun stableId(reference: LoanReference): String =
        RegistryEntityIdFactory.stableLoanId(reference.bank.id, reference.loanType.name)

    private suspend fun findRegistryEntry(reference: LoanReference): LoanRegistryEntity? =
        dao.get(reference.bank.id, reference.loanType.name)

    private fun toEntry(entity: LoanRegistryEntity): LoanRegistryEntry =
        LoanRegistryEntry(
            id = entity.id,
            bank = Bank(entity.bankId),
            loanType = LoanType.valueOf(entity.loanType),
            ownership = OwnershipStatus.valueOf(entity.ownershipStatus),
            displayName = entity.displayName,
            firstSeenRawSmsId = entity.firstSeenRawSmsId,
            lastSeenRawSmsId = entity.lastSeenRawSmsId,
        )

    companion object {
        fun from(database: MasroofDatabase, accessGate: DatabaseAccessGate = DatabaseAccessGate()): RoomLoanRegistryRepository =
            RoomLoanRegistryRepository(
                dao = database.loanRegistryDao(),
                bankRegistryDao = database.bankRegistryDao(),
                accessGate = accessGate,
            )
    }
}
