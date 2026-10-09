package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class DebitCardScopeFactoryTest {
    @Test
    fun fromRegistry_infersLinkedAccountFromSmsWhenRegistryLinkMissing() {
        val body = "شراء من نقاط البيع\nبطاقة مدى: 2210\nخصمت من حساب: 3001"
        val parsedRecords = listOf(parsedRecord("2210", body))
        val debit = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "2210",
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.DEBIT,
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )
        val accounts = listOf(
            com.baraa.masroof.domain.model.AccountRegistryEntry.forTest(
                bank = Bank.BANK_ALJAZIRA,
                maskedNumber = "12345678903001",
                ownership = OwnershipStatus.OWNED,
                firstSeenRawSmsId = "sms",
                lastSeenRawSmsId = "sms",
            ),
        )

        val scope = DebitCardScopeFactory.fromRegistry(
            cards = listOf(debit),
            parsedRecords = parsedRecords,
            registryAccounts = accounts,
        )

        val cardId = FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "2210")
        val accountId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "12345678903001")
        assertEquals(setOf(cardId), scope.ownedDebitCardContainerIds)
        assertEquals(mapOf(cardId to accountId), scope.debitCardLinkedAccountIds)
    }

    private fun parsedRecord(cardLast4: String, body: String): ParsedEventRecord {
        val event = ParsedEvent(
            id = "evt-$cardLast4",
            rawSmsId = "sms-$cardLast4",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            direction = MoneyDirection.OUTGOING,
            amount = null,
            purchaseChannel = null,
            cardRef = CardReference(Bank.BANK_ALJAZIRA, cardLast4),
            sourceAccountRef = null,
            destinationAccountRef = null,
            merchant = null,
            counterparty = body,
            occurredAt = Instant.parse("2026-08-03T10:24:00Z"),
            bankNetworkType = null,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        )
        return ParsedEventRecord(event = event, details = ParsedEventDetails(debitSourceAccountLast4 = "3001"))
    }
}
