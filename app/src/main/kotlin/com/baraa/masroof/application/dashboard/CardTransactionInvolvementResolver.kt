package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.FinancialContainerIdParser
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Maps each transaction to card keys (`bankId:last4`) from linked parsed events and card containers.
 * The bank stays in the key, so the same last4 at two banks never shares a card.
 */
object CardTransactionInvolvementResolver {
    fun buildIndex(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
    ): Map<String, Set<String>> {
        if (transactions.isEmpty()) return emptyMap()
        val parsedById = parsedRecords.associateBy { it.event.id }
        return transactions.associate { tx ->
            val keys = linkedCardKeys(tx, parsedById).toMutableSet()
            FinancialContainerIdParser.cardLast4(tx.sourceContainerId)?.let { last4 ->
                FinancialContainerIdParser.cardBankId(tx.sourceContainerId)?.let { bankId ->
                    keys += cardKey(bankId, last4)
                }
            }
            FinancialContainerIdParser.cardLast4(tx.destinationContainerId)?.let { last4 ->
                FinancialContainerIdParser.cardBankId(tx.destinationContainerId)?.let { bankId ->
                    keys += cardKey(bankId, last4)
                }
            }
            tx.id to keys
        }
    }

    fun resolvePrimaryCardKey(
        transaction: FinancialTransaction,
        index: Map<String, Set<String>>,
    ): String? = index[transaction.id]?.minOrNull()

    fun matchesCard(
        transactionId: String,
        bankId: String,
        last4: String,
        index: Map<String, Set<String>>,
    ): Boolean = cardKey(bankId, last4) in index[transactionId].orEmpty()

    private fun linkedCardKeys(
        tx: FinancialTransaction,
        parsedById: Map<String, ParsedEventRecord>,
    ): Set<String> =
        tx.linkedParsedEventIds.mapNotNull { eventId ->
            val record = parsedById[eventId] ?: return@mapNotNull null
            cardKeyFromEvent(record)
        }.toSet()

    private fun cardKeyFromEvent(record: ParsedEventRecord): String? =
        record.event.cardRef?.toCardKey()

    private fun CardReference.toCardKey(): String? {
        val digits = last4 ?: return null
        return cardKey(bank.id, digits)
    }

    fun cardKey(bankId: String, last4: String): String = "$bankId:$last4"

    fun cardContainerId(cardKey: String): String? {
        val parts = cardKey.split(":", limit = 2)
        if (parts.size != 2) return null
        val bank = Bank.fromId(parts[0])
        return FinancialContainerIdFactory.cardId(bank, parts[1])
    }
}
