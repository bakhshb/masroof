package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
        val containerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")!!
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
        )

        val index = AccountTransactionInvolvementResolver.buildIndex(
            transactions = listOf(tx),
            parsedRecords = listOf(record),
            ownedAccounts = listOf(owned),
        )

        assertEquals(setOf(containerId), index["cash"])
    }

    @Test
    fun cashWithdrawal_hisabRaqam_mapsToOwnedAccount() {
        val owned = AccountRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            maskedNumber = "3001",
            ownership = OwnershipStatus.OWNED,
            firstSeenRawSmsId = null,
            lastSeenRawSmsId = null,
        )
        val containerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")!!
        val tx = tx(
            id = "cash-atm",
            type = FinancialTransactionType.CASH_WITHDRAWAL,
            amount = "2200.00",
            source = null,
            linked = listOf("evt-cash-atm"),
        )
        val record = parsedRecord(
            id = "evt-cash-atm",
            family = MessageFamily.WITHDRAWAL,
            sourceLast4 = "3001",
        )

        val index = AccountTransactionInvolvementResolver.buildIndex(
            transactions = listOf(tx),
            parsedRecords = listOf(record),
            ownedAccounts = listOf(owned),
        )

        assertEquals(setOf(containerId), index["cash-atm"])
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
        val otherAccount = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")!!
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

    private fun tx(
        id: String,
        type: FinancialTransactionType,
        amount: String,
        source: String? = null,
        linked: List<String> = listOf("evt-$id"),
    ): FinancialTransaction =
        FinancialTransaction(
            id = id,
            type = type,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = Instant.parse("2026-08-10T12:00:00Z"),
            sourceContainerId = source,
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = linked,
        )

    private fun parsedRecord(
        id: String,
        family: MessageFamily,
        sourceLast4: String? = null,
        rawBody: String? = null,
    ): ParsedEventRecord {
        val event = ParsedEvent(
            id = id,
            rawSmsId = "sms-$id",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = family,
            direction = MoneyDirection.INCOMING,
            amount = Money.of("1.00", Currency.SAR),
            purchaseChannel = null,
            sourceAccountRef = sourceLast4?.let { AccountReference(Bank.BANK_ALJAZIRA, it) },
            destinationAccountRef = null,
            cardRef = null,
            merchant = null,
            counterparty = rawBody,
            occurredAt = Instant.parse("2026-08-10T12:00:00Z"),
            bankNetworkType = null,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        )
        return ParsedEventRecord(event = event, details = ParsedEventDetails())
    }
}
