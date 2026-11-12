package com.baraa.masroof.data.room.mapper

import com.baraa.masroof.data.room.entity.AccountRegistryEntity
import com.baraa.masroof.data.room.entity.CardRegistryEntity
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.OwnershipStatus

object RegistryMapper {
    fun toAccountEntry(entity: AccountRegistryEntity): AccountRegistryEntry =
        AccountRegistryEntry(
            id = entity.id,
            bank = Bank(entity.bankId),
            maskedNumber = entity.maskedNumber,
            ownership = OwnershipStatus.valueOf(entity.ownershipStatus),
            displayName = entity.displayName,
            accountType = runCatching { AccountType.valueOf(entity.accountType) }.getOrDefault(AccountType.CURRENT),
            firstSeenRawSmsId = entity.firstSeenRawSmsId,
            lastSeenRawSmsId = entity.lastSeenRawSmsId,
        )

    fun toCardEntry(entity: CardRegistryEntity): CardRegistryEntry =
        CardRegistryEntry(
            id = entity.id,
            bank = Bank(entity.bankId),
            last4 = entity.last4,
            ownership = OwnershipStatus.valueOf(entity.ownershipStatus),
            displayName = entity.displayName,
            cardNetwork = entity.cardNetwork?.let { runCatching { CardNetwork.valueOf(it) }.getOrNull() },
            cardType = entity.cardType?.let { runCatching { CardType.valueOf(it) }.getOrNull() },
            linkedAccountBankId = entity.linkedAccountBankId,
            linkedAccountMaskedNumber = entity.linkedAccountMaskedNumber,
            parentCardLast4 = entity.parentCardLast4,
            cardRole = entity.cardRole?.let { runCatching { CardRole.valueOf(it) }.getOrNull() },
            creditFacilityId = entity.creditFacilityId,
            firstSeenRawSmsId = entity.firstSeenRawSmsId,
            lastSeenRawSmsId = entity.lastSeenRawSmsId,
        )
}
