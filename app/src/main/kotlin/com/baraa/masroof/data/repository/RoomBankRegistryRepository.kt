package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.dao.BankRegistryDao
import com.baraa.masroof.data.room.entity.BankRegistryEntity
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankRegistryEntry
import com.baraa.masroof.domain.ownership.RegistryIdentity
import com.baraa.masroof.domain.repository.BankRegistryRepository

class RoomBankRegistryRepository(
    private val dao: BankRegistryDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : BankRegistryRepository {
    override suspend fun ensureKnown(bank: Bank): Unit = accessGate.withAccess<Unit> {
        if (!RegistryIdentity.isKnownBank(bank)) return@withAccess
        dao.insertIfAbsent(BankRegistryEntity(bankId = bank.id))
    }

    override suspend fun listAll(): List<BankRegistryEntry> = accessGate.withAccess {
        dao.listAll().map { entity ->
            BankRegistryEntry(
                bank = Bank(entity.bankId),
                displayName = entity.displayName,
            )
        }
    }
}
