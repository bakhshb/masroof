package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.testsupport.parsedRecord
import com.baraa.masroof.testsupport.tx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountTransactionInvolvementResolverTest {
    @Test
    fun smsResolvedWithdrawal_mapsToOwnedAccount() {
        val owned = AccountRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            maskedNumber = "3001",
            ownership = OwnershipStatus.OWNED,
            firstSeenRawSmsId = null,
            lastSeenRawSmsId = null,
        )
        val containerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val tx = tx(
            id = "cash",
            type = FinancialTransactionType.CASH_WITHDRAWAL,
            amount = "500.00",
            source = null,
            linked = listOf("evt-cash"),
        )
        val record = parsedRecord(
            id = "evt-cash",
            family = MessageFamily.WITHDRAWAL,
            sourceLast4 = "3001",
            direction = MoneyDirection.INCOMING,
        )

        val index = AccountTransactionInvolvementResolver.buildIndex(
            transactions = listOf(tx),
            parsedRecords = listOf(record),
            ownedAccounts = listOf(owned),
        )

        assertEquals(setOf(containerId), index["cash"])
    }

    @Test
    fun unrelatedTransaction_notMapped() {
        val owned = AccountRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            maskedNumber = "3002",
            ownership = OwnershipStatus.OWNED,
            firstSeenRawSmsId = null,
            lastSeenRawSmsId = null,
        )
        val otherAccount = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val tx = tx(
            id = "other",
            type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            amount = "100.00",
            source = otherAccount,
            linked = emptyList(),
        )

        val index = AccountTransactionInvolvementResolver.buildIndex(
            transactions = listOf(tx),
            parsedRecords = emptyList(),
            ownedAccounts = listOf(owned),
        )

        assertTrue(index["other"].isNullOrEmpty())
    }
}
