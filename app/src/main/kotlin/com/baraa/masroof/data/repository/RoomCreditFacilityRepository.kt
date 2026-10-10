package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.dao.CreditFacilityDao
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CreditFacilityEntry
import com.baraa.masroof.domain.repository.CreditFacilityRepository

class RoomCreditFacilityRepository(
    private val dao: CreditFacilityDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : CreditFacilityRepository {
    override suspend fun listAll(): List<CreditFacilityEntry> = accessGate.withAccess {
        dao.listAll().map(::toEntry)
    }

    override suspend fun listByBank(bankId: String): List<CreditFacilityEntry> = accessGate.withAccess {
        dao.listByBank(bankId).map(::toEntry)
    }

    private fun toEntry(entity: com.baraa.masroof.data.room.entity.CreditFacilityEntity): CreditFacilityEntry =
        CreditFacilityEntry(
            id = entity.id,
            bank = Bank(entity.bankId),
            primaryLast4 = entity.primaryLast4,
            displayName = entity.displayName,
        )
}
