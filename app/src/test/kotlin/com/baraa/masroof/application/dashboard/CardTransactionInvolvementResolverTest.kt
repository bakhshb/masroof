package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CardTransactionInvolvementResolverTest {
    @Test
    fun buildIndex_usesParsedCardRefAndContainerIds() {
        val cardId = FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "2210")!!
        val txWithContainer = transaction(
            id = "container",
            source = cardId,
            linked = emptyList(),
        )
        val txWithParsedRef = transaction(
            id = "parsed",
            source = null,
            linked = listOf("evt-1"),
        )
        val parsedRecords = listOf(
            parsedRecord(
                id = "evt-1",
                cardLast4 = "3109",
            ),
        )

        val index = CardTransactionInvolvementResolver.buildIndex(
            transactions = listOf(txWithContainer, txWithParsedRef),
            parsedRecords = parsedRecords,
        )

        assertEquals(
            setOf(CardTransactionInvolvementResolver.cardKey(Bank.BANK_ALJAZIRA.id, "2210")),
            index["container"],
        )
        assertEquals(
            setOf(CardTransactionInvolvementResolver.cardKey(Bank.BANK_ALJAZIRA.id, "3109")),
            index["parsed"],
        )
    }

    @Test
    fun resolvePrimaryCardKey_isDeterministicWhenMultipleCards() {
        val index = mapOf(
            "tx" to setOf(
                CardTransactionInvolvementResolver.cardKey(Bank.BANK_ALJAZIRA.id, "3109"),
                CardTransactionInvolvementResolver.cardKey(Bank.BANK_ALJAZIRA.id, "2210"),
            ),
        )

        val key = CardTransactionInvolvementResolver.resolvePrimaryCardKey(
            transaction = transaction(id = "tx", source = null, linked = emptyList()),
            index = index,
        )

        assertEquals(CardTransactionInvolvementResolver.cardKey(Bank.BANK_ALJAZIRA.id, "2210"), key)
    }

    @Test
    fun matchesCard_checksCardKeyMembership() {
        val index = mapOf(
            "tx" to setOf(CardTransactionInvolvementResolver.cardKey(Bank.BANK_ALJAZIRA.id, "2210")),
        )

        assertTrue(
            CardTransactionInvolvementResolver.matchesCard(
                transactionId = "tx",
                bankId = Bank.BANK_ALJAZIRA.id,
                last4 = "2210",
                index = index,
            ),
        )
    }

    @Test
    fun buildIndex_doesNotInferCardFromSmsBodyWhenParsedCardRefMissing() {
        val txWithParsedRef = transaction(
            id = "atm",
            source = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            linked = listOf("evt-atm"),
        )
        val parsedRecords = listOf(
            parsedRecordWithoutCardRef(
                id = "evt-atm",
                rawSmsId = "sms-atm",
            ),
        )

        val index = CardTransactionInvolvementResolver.buildIndex(
            transactions = listOf(txWithParsedRef),
            parsedRecords = parsedRecords,
        )

        assertEquals(emptySet<String>(), index["atm"])
    }

    private fun parsedRecordWithoutCardRef(
        id: String,
        rawSmsId: String,
    ): ParsedEventRecord {
        val event = com.baraa.masroof.domain.model.ParsedEvent(
            id = id,
            rawSmsId = rawSmsId,
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.WITHDRAWAL,
            direction = MoneyDirection.OUTGOING,
            amount = Money.of("2200.00", Currency.SAR),
            purchaseChannel = null,
            sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destinationAccountRef = null,
            cardRef = null,
            merchant = null,
            counterparty = null,
            occurredAt = Instant.parse("2026-08-02T17:41:00Z"),
            bankNetworkType = null,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        )
        return ParsedEventRecord(event = event, details = ParsedEventDetails())
    }

    @Test
    fun sameLast4AtTwoBanks_staysTwoCardKeys() {
        val aljazira = FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "7271")
        val other = FinancialContainerIdFactory.cardId(Bank("OTHER_BANK"), "7271")
        val index = CardTransactionInvolvementResolver.buildIndex(
            transactions = listOf(
                transaction(id = "alj", source = aljazira, linked = emptyList()),
                transaction(id = "other", source = other, linked = emptyList()),
            ),
            parsedRecords = emptyList(),
        )

        assertEquals(setOf("BANK_ALJAZIRA:7271"), index.getValue("alj"))
        assertEquals(setOf("OTHER_BANK:7271"), index.getValue("other"))
        assertTrue(
            CardTransactionInvolvementResolver.matchesCard("alj", "BANK_ALJAZIRA", "7271", index),
        )
        assertTrue(
            !CardTransactionInvolvementResolver.matchesCard("alj", "OTHER_BANK", "7271", index),
        )
    }

    private fun transaction(
        id: String,
        source: String?,
        linked: List<String>,
    ): FinancialTransaction =
        FinancialTransaction(
            id = id,
            type = FinancialTransactionType.EXPENSE,
            amount = Money.of("10.00", Currency.SAR),
            occurredAt = Instant.parse("2026-08-05T11:05:00Z"),
            sourceContainerId = source,
            destinationContainerId = null,
            linkedParsedEventIds = linked,
            merchant = null,
            counterparty = null,
            categoryId = null,
        )

    private fun parsedRecord(
        id: String,
        cardLast4: String,
    ): ParsedEventRecord {
        val event = ParsedEvent(
            id = id,
            rawSmsId = "sms-$id",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            direction = MoneyDirection.OUTGOING,
            amount = Money.of("1.00", Currency.SAR),
            purchaseChannel = null,
            sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destinationAccountRef = null,
            cardRef = CardReference(Bank.BANK_ALJAZIRA, cardLast4),
            merchant = null,
            counterparty = null,
            occurredAt = Instant.parse("2026-08-05T11:05:00Z"),
            bankNetworkType = null,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        )
        return ParsedEventRecord(event = event, details = ParsedEventDetails())
    }
}
