package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.testsupport.parsedRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class DebitCardScopeFactoryTest {
    @Test
    fun fromRegistry_infersLinkedAccountFromSmsWhenRegistryLinkMissing() {
        val body = "شراء من نقاط البيع\nبطاقة مدى: 2210\nخصمت من حساب: 3001"
        val parsedRecords = listOf(parsedRecord(cardLast4 = "2210", body = body, id = "evt-2210", rawSmsId = "sms-2210", amount = null, debitSourceAccountLast4 = "3001"))
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
}
