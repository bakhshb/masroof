package com.baraa.masroof.application.dashboard

import com.baraa.masroof.testsupport.EngineFixtureParser
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.assembly.TransactionAssembler
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reproduction for Mada Google Pay POS purchase missing from debit card dashboard (card 8219).
 */
class MadaGooglePayPosReproTest {
    private val pipeline = EngineFixtureParser()
    private val zoneId = ZoneId.of("Asia/Riyadh")
    private val salaryPeriod = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-11"))

    private val googlePayBody = """
        شراء عبر نقاط البيع (Google Pay)
        بطاقة مدى: 8219
        لدى: MALAYSIA FOODS RESTA
        بمبلغ: 127.00 SAR
        في: 13:24 03-08-2026
    """.trimIndent()

    @Test
    fun googlePayMadaPos_assemblesWithCardSourceOnly() {
        val (parsed, tx) = parseAndAssemble(googlePayBody)

        // Google Pay Mada carries no account line: no bank-only phantom source reference.
        assertNull(parsed.event.sourceAccountRef)
        assertEquals(FinancialTransactionType.EXPENSE, tx.type)
        assertEquals(
            FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "8219"),
            tx.sourceContainerId,
        )
    }

    @Test
    fun googlePayMadaPos_countsTowardLinkedDebitCardSpending() {
        val (parsedRecord, tx) = parseAndAssemble(googlePayBody)
        val debit = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "8219",
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.DEBIT,
            linkedAccountBankId = Bank.BANK_ALJAZIRA.id,
            linkedAccountMaskedNumber = "3001",
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )
        val owned = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")

        val result = DebitCardOverviewBuilder.buildSpendingByCardKey(
            salaryPeriod = salaryPeriod,
            debitCards = listOf(debit),
            transactions = listOf(tx),
            parsedRecords = listOf(parsedRecord),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            ownedAccountContainerIds = setOf(owned),
            zoneId = zoneId,
        )

        // Google Pay Mada POS must count toward linked debit card spending.
        assertEquals(
            "Expected Google Pay Mada POS to count toward card 8219 salary-period spending",
            BigDecimal("127.00"),
            result.spendingByCardKey["BANK_ALJAZIRA:8219"]?.amount,
        )
        assertTrue(
            "Expected transaction in debit spend involvement index",
            tx.id in result.transactionDebitSpendInvolvement,
        )
    }

    @Test
    fun googlePayMadaPos_countsTowardFleetCurrentAccountPosPurchases() {
        val (parsedRecord, tx) = parseAndAssemble(googlePayBody)
        val debit = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "8219",
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.DEBIT,
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )
        val owned = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val debitCardScope = DebitCardScopeFactory.fromRegistry(listOf(debit))

        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(tx),
            parsedRecords = listOf(parsedRecord),
            ownedAccountContainerIds = setOf(owned),
            debitCardScope = debitCardScope,
        )

        assertEquals(
            Money.of("127.00", Currency.SAR),
            summary.outflow.posPurchases,
        )
    }

    @Test
    fun creditGooglePayPos_doesNotCountTowardMadaSpendingOrCurrentAccountPos() {
        val creditGooglePayBody = """
            شراء عبر نقاط البيع (Google Pay)
            بطاقة ائتمانية: 8219
            لدى: ananinja.com
            بمبلغ: 75.00 SAR
            الرصيد المتاح: 14569.09 SAR
            إجمالي المبلغ المستحق:3921.11 SAR
        """.trimIndent()

        val (parsedRecord, tx) = parseAndAssemble(creditGooglePayBody, rawSmsId = "sms-credit-google-pay")
        val debit = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "8219",
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.DEBIT,
            linkedAccountBankId = Bank.BANK_ALJAZIRA.id,
            linkedAccountMaskedNumber = "3001",
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )
        val owned = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val debitCardScope = DebitCardScopeFactory.fromRegistry(listOf(debit))

        val result = DebitCardOverviewBuilder.buildSpendingByCardKey(
            salaryPeriod = salaryPeriod,
            debitCards = listOf(debit),
            transactions = listOf(tx),
            parsedRecords = listOf(parsedRecord),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            ownedAccountContainerIds = setOf(owned),
            zoneId = zoneId,
        )

        assertEquals(
            "Credit Google Pay must not increment Mada salary-period spending",
            BigDecimal("0.00"),
            result.spendingByCardKey["BANK_ALJAZIRA:8219"]?.amount,
        )
        assertTrue(
            "Credit Google Pay must not appear in debit spend involvement index",
            tx.id !in result.transactionDebitSpendInvolvement,
        )

        val accountSummary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(tx),
            parsedRecords = listOf(parsedRecord),
            ownedAccountContainerIds = setOf(owned),
            debitCardScope = debitCardScope,
        )
        assertEquals(
            Money.zero(Currency.SAR),
            accountSummary.outflow.posPurchases,
        )
    }

    /** Parses [body] and assembles it with an owned card, as reconciliation would. */
    private fun parseAndAssemble(
        body: String,
        rawSmsId: String = "sms-google-pay",
    ): Pair<ParsedEventRecord, FinancialTransaction> {
        val at = Instant.parse("2026-08-03T10:24:00Z")
        val parsed = pipeline.parse(
            SmsParseInput(rawSmsId = rawSmsId, sender = "AlJazira", body = body, receivedAt = at),
        ) as ParseResult.Success
        val assembled = TransactionAssembler.assembleSingle(
            event = parsed.event,
            receivedAt = at,
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        ) as TransactionAssembler.Outcome.Assembled
        return ParsedEventRecord(event = parsed.event, details = parsed.details) to
            assembled.transaction.copy(occurredAt = at)
    }
}
