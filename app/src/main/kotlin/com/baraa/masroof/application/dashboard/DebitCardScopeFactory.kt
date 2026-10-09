package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.parsing.repository.ParsedEventRecord

data class DebitCardScopeFacts(
    val ownedDebitCardContainerIds: Set<String>,
    val debitCardLinkedAccountIds: Map<String, String>,
)

object DebitCardScopeFactory {
    fun fromRegistry(
        cards: List<CardRegistryEntry>,
        parsedRecords: List<ParsedEventRecord> = emptyList(),
        registryAccounts: List<AccountRegistryEntry> = emptyList(),
    ): DebitCardScopeFacts {
        val ownedDebit = cards.filter {
            it.ownership == OwnershipStatus.OWNED &&
                CardRegistryDebitClassifier.isDebitRegistryEntry(
                    it,
                    parsedRecords = parsedRecords,
                )
        }
        val ownedDebitCardContainerIds = ownedDebit.mapNotNull { entry ->
            FinancialContainerIdFactory.cardId(entry.bank, entry.last4)
        }.toSet()
        val debitCardLinkedAccountIds = ownedDebit.mapNotNull { entry ->
            val cardId = FinancialContainerIdFactory.cardId(entry.bank, entry.last4) ?: return@mapNotNull null
            val accountId = resolveLinkedAccountId(entry, parsedRecords, registryAccounts)
                ?: return@mapNotNull null
            cardId to accountId
        }.toMap()
        return DebitCardScopeFacts(
            ownedDebitCardContainerIds = ownedDebitCardContainerIds,
            debitCardLinkedAccountIds = debitCardLinkedAccountIds,
        )
    }

    private fun resolveLinkedAccountId(
        entry: CardRegistryEntry,
        parsedRecords: List<ParsedEventRecord>,
        registryAccounts: List<AccountRegistryEntry>,
    ): String? {
        entry.linkedAccount?.let(FinancialContainerIdFactory::accountId)?.let { return it }
        val inferredLast4 = DebitLinkedAccountInferrer.inferAccountLast4(
            bank = entry.bank,
            cardLast4 = entry.last4,
            parsedRecords = parsedRecords,
        ) ?: return null
        val account = registryAccounts.find {
            it.bank == entry.bank &&
                (it.maskedNumber == inferredLast4 || it.maskedNumber.endsWith(inferredLast4))
        } ?: return FinancialContainerIdFactory.accountId(entry.bank, inferredLast4)
        return FinancialContainerIdFactory.accountId(account.bank, account.maskedNumber)
    }
}
