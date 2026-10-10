package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.dao.CardRegistryDao
import com.baraa.masroof.data.room.entity.CardRegistryEntity
import com.baraa.masroof.data.room.mapper.RegistryMapper
import com.baraa.masroof.domain.ids.RegistryEntityIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.ownership.RegistryIdentity
import com.baraa.masroof.domain.repository.CardRegistryRepository

class RoomCardRegistryRepository(
    private val dao: CardRegistryDao,
    private val bankRegistryDao: com.baraa.masroof.data.room.dao.BankRegistryDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : CardRegistryRepository {
    override suspend fun observe(reference: CardReference, rawSmsId: String): Unit = accessGate.withAccess<Unit> {
        if (!RegistryIdentity.isKnownBank(reference.bank)) return@withAccess
        val last4 = reference.last4?.trim().orEmpty()
        if (last4.isEmpty()) return@withAccess

        bankRegistryDao.insertIfAbsent(
            com.baraa.masroof.data.room.entity.BankRegistryEntity(bankId = reference.bank.id),
        )

        dao.observeAtomic(
            entity = CardRegistryEntity(
                id = RegistryEntityIdFactory.newCardId(),
                bankId = reference.bank.id,
                last4 = last4,
                ownershipStatus = OwnershipStatus.UNKNOWN.name,
                firstSeenRawSmsId = rawSmsId,
                lastSeenRawSmsId = rawSmsId,
            ),
            rawSmsId = rawSmsId,
        )
    }

    override suspend fun setOwnership(reference: CardReference, status: OwnershipStatus): Unit = accessGate.withAccess<Unit> {
        RegistryIdentity.requireKnownBank(reference.bank, "CardRegistry.setOwnership")
        val last4 = reference.last4?.trim().orEmpty()
        require(last4.isNotEmpty()) { "last4 required to set ownership" }

        dao.setOwnershipAtomic(
            entity = CardRegistryEntity(
                id = RegistryEntityIdFactory.newCardId(),
                bankId = reference.bank.id,
                last4 = last4,
                ownershipStatus = status.name,
                firstSeenRawSmsId = null,
                lastSeenRawSmsId = null,
            ),
            ownershipStatus = status.name,
        )
    }

    override suspend fun resolve(reference: CardReference): OwnershipStatus = accessGate.withAccess {
        if (!RegistryIdentity.isKnownBank(reference.bank)) return@withAccess OwnershipStatus.UNKNOWN
        val last4 = reference.last4?.trim().orEmpty()
        if (last4.isEmpty()) return@withAccess OwnershipStatus.UNKNOWN
        val entry = dao.get(reference.bank.id, last4) ?: return@withAccess OwnershipStatus.UNKNOWN
        return@withAccess OwnershipStatus.valueOf(entry.ownershipStatus)
    }

    override suspend fun get(reference: CardReference): CardRegistryEntry? = accessGate.withAccess {
        if (!RegistryIdentity.isKnownBank(reference.bank)) return@withAccess null
        val last4 = reference.last4?.trim().orEmpty()
        if (last4.isEmpty()) return@withAccess null
        return@withAccess dao.get(reference.bank.id, last4)?.let(RegistryMapper::toCardEntry)
    }

    override suspend fun listAll(): List<CardRegistryEntry> = accessGate.withAccess {
        dao.listAll().map(RegistryMapper::toCardEntry)
    }

    override suspend fun updateDisplayName(reference: CardReference, displayName: String?): Unit = accessGate.withAccess<Unit> {
        val last4 = requireLast4(reference)
        requireExisting(reference.bank.id, last4)
        requireUpdated(
            dao.updateDisplayName(reference.bank.id, last4, displayName?.trim()?.ifEmpty { null }),
        )
    }

    override suspend fun updateCardNetwork(reference: CardReference, network: CardNetwork?): Unit = accessGate.withAccess<Unit> {
        val last4 = requireLast4(reference)
        requireExisting(reference.bank.id, last4)
        requireUpdated(dao.updateCardNetwork(reference.bank.id, last4, network?.name))
    }

    override suspend fun updateCardType(reference: CardReference, cardType: CardType?): Unit = accessGate.withAccess<Unit> {
        val last4 = requireLast4(reference)
        requireExisting(reference.bank.id, last4)
        requireUpdated(dao.updateCardType(reference.bank.id, last4, cardType?.name))
    }

    override suspend fun linkDebitToAccount(card: CardReference, account: AccountReference): Unit = accessGate.withAccess<Unit> {
        val last4 = requireLast4(card)
        RegistryIdentity.requireKnownBank(account.bank, "CardRegistry.linkDebitToAccount")
        require(card.bank == account.bank) { "cross_bank_link" }
        requireExisting(card.bank.id, last4)
        val masked = account.maskedNumber?.trim().orEmpty()
        require(masked.isNotEmpty()) { "account_masked_number_required" }
        dao.linkDebitAtomic(
            bankId = card.bank.id,
            last4 = last4,
            linkedAccountBankId = account.bank.id,
            linkedAccountMaskedNumber = masked,
            defaultMadaWhenNetworkMissing = true,
        )
        requireExisting(card.bank.id, last4)
    }

    override suspend fun markAsDebit(reference: CardReference): Unit = accessGate.withAccess<Unit> {
        val last4 = requireLast4(reference)
        requireExisting(reference.bank.id, last4)
        dao.markDebitAtomic(
            bankId = reference.bank.id,
            last4 = last4,
            defaultMadaWhenNetworkMissing = true,
        )
        requireExisting(reference.bank.id, last4)
    }

    override suspend fun setPrimaryCard(reference: CardReference): Unit = accessGate.withAccess<Unit> {
        val last4 = requireLast4(reference)
        val existing = requireExisting(reference.bank.id, last4)
        if (existing.cardType == CardType.DEBIT.name) {
            throw IllegalArgumentException("debit_card_cannot_be_primary")
        }
        dao.promoteToPrimaryAtomic(reference.bank.id, last4)
        requireExisting(reference.bank.id, last4)
    }

    override suspend fun setSupplementaryCard(reference: CardReference, primaryLast4: String): Unit = accessGate.withAccess<Unit> {
        val last4 = requireLast4(reference)
        val parentLast4 = primaryLast4.trim()
        require(parentLast4.isNotEmpty()) { "primaryLast4 required" }
        require(last4 != parentLast4) { "cannot_be_own_parent" }
        val parent = dao.get(reference.bank.id, parentLast4)
            ?: throw IllegalArgumentException("primary_not_found")
        require(parent.cardRole == CardRole.PRIMARY.name) { "parent_not_primary" }
        requireExisting(reference.bank.id, last4)
        dao.setSupplementaryAtomic(reference.bank.id, last4, parentLast4)
        requireExisting(reference.bank.id, last4)
    }

    override suspend fun clearCardRole(reference: CardReference): Unit = accessGate.withAccess<Unit> {
        val last4 = requireLast4(reference)
        requireExisting(reference.bank.id, last4)
        dao.clearFacilityRoleAtomic(reference.bank.id, last4)
        requireExisting(reference.bank.id, last4)
    }

    private fun requireLast4(reference: CardReference): String {
        RegistryIdentity.requireKnownBank(reference.bank, "CardRegistry")
        val last4 = reference.last4?.trim().orEmpty()
        require(last4.isNotEmpty()) { "last4 required" }
        return last4
    }

    private suspend fun requireExisting(bankId: String, last4: String): CardRegistryEntity {
        return dao.get(bankId, last4)
            ?: throw IllegalArgumentException("card_not_found")
    }

    private fun requireUpdated(affectedRows: Int) {
        if (affectedRows == 0) {
            throw IllegalArgumentException("card_update_failed")
        }
    }

    companion object {
        fun from(database: MasroofDatabase, accessGate: DatabaseAccessGate = DatabaseAccessGate()): RoomCardRegistryRepository =
            RoomCardRegistryRepository(
                dao = database.cardRegistryDao(),
                bankRegistryDao = database.bankRegistryDao(),
                accessGate = accessGate,
            )
    }
}
