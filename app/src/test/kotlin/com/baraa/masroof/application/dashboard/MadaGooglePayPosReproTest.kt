package com.baraa.masroof.application.dashboard

import com.baraa.masroof.bank.aljazira.AlJaziraParsingPipeline
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.assembly.TransactionAssembler
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.RawSms
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
    private val pipeline = AlJaziraParsingPipeline()
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
    fun googlePayMadaPos_parsesWithoutSourceAccount() {
        val result = pipeline.parse(
            SmsParseInput(
                rawSmsId = "sms-google-pay",
                sender = "AlJazira",
                body = googlePayBody,
                receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
            ),
        ) as ParseResult.Success

        assertEquals(MessageFamily.PURCHASE, result.event.messageFamily)
        assertEquals("8219", result.event.cardRef?.last4)
        assertEquals("MALAYSIA FOODS RESTA", result.event.merchant)
        assertEquals(Money.of("127.00", Currency.SAR), result.event.amount)
        assertNull(result.event.sourceAccountRef)
    }

    @Test
    fun googlePayMadaPos_assemblesWithCardSourceOnly() {
        val parsed = pipeline.parse(
            SmsParseInput(
                rawSmsId = "sms-google-pay",
                sender = "AlJazira",
                body = googlePayBody,
                receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
            ),
        ) as ParseResult.Success

        val assembled = TransactionAssembler.assembleSingle(
            event = parsed.event,
            receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        ) as TransactionAssembler.Outcome.Assembled

        assertEquals(FinancialTransactionType.EXPENSE, assembled.transaction.type)
        assertEquals(
            FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "8219"),
            assembled.transaction.sourceContainerId,
        )
    }

    @Test
    fun googlePayMadaPos_countsTowardLinkedDebitCardSpending() {
        val parsed = pipeline.parse(
            SmsParseInput(
                rawSmsId = "sms-google-pay",
                sender = "AlJazira",
                body = googlePayBody,
                receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
            ),
        ) as ParseResult.Success

        val assembled = TransactionAssembler.assembleSingle(
            event = parsed.event,
            receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        ) as TransactionAssembler.Outcome.Assembled

        val tx = assembled.transaction.copy(occurredAt = Instant.parse("2026-08-03T10:24:00Z"))
        val parsedRecord = ParsedEventRecord(event = parsed.event, details = parsed.details)
        val rawSmsById = mapOf(
            "sms-google-pay" to RawSms(
                id = "sms-google-pay",
                sender = "AlJazira",
                body = googlePayBody,
                receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
                deviceMessageId = "1",
                bodyHash = "h",
            ),
        )
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
            ownedAccountLast4s = setOf("3001"),
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
        val parsed = pipeline.parse(
            SmsParseInput(
                rawSmsId = "sms-google-pay",
                sender = "AlJazira",
                body = googlePayBody,
                receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
            ),
        ) as ParseResult.Success

        val assembled = TransactionAssembler.assembleSingle(
            event = parsed.event,
            receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        ) as TransactionAssembler.Outcome.Assembled

        val tx = assembled.transaction.copy(occurredAt = Instant.parse("2026-08-03T10:24:00Z"))
        val parsedRecord = ParsedEventRecord(event = parsed.event, details = parsed.details)
        val rawSmsById = mapOf(
            "sms-google-pay" to RawSms(
                id = "sms-google-pay",
                sender = "AlJazira",
                body = googlePayBody,
                receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
                deviceMessageId = "1",
                bodyHash = "h",
            ),
        )
        val debit = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "8219",
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.DEBIT,
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )
        val owned = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val cardId = FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "8219")
        val debitCardScope = DebitCardScopeFactory.fromRegistry(listOf(debit))

        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(tx),
            parsedRecords = listOf(parsedRecord),
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3001"),
            debitCardScope = debitCardScope,
        )

        assertEquals(
            Money.of("127.00", Currency.SAR),
            summary.outflow.posPurchases,
        )
        requireNotNull(cardId)
        requireNotNull(owned)
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

        val parsed = pipeline.parse(
            SmsParseInput(
                rawSmsId = "sms-credit-google-pay",
                sender = "AlJazira",
                body = creditGooglePayBody,
                receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
            ),
        ) as ParseResult.Success

        val assembled = TransactionAssembler.assembleSingle(
            event = parsed.event,
            receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        ) as TransactionAssembler.Outcome.Assembled

        val tx = assembled.transaction.copy(occurredAt = Instant.parse("2026-08-03T10:24:00Z"))
        val parsedRecord = ParsedEventRecord(event = parsed.event, details = parsed.details)
        val rawSmsById = mapOf(
            "sms-credit-google-pay" to RawSms(
                id = "sms-credit-google-pay",
                sender = "AlJazira",
                body = creditGooglePayBody,
                receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
                deviceMessageId = "2",
                bodyHash = "h2",
            ),
        )
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
            ownedAccountLast4s = setOf("3001"),
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
            ownedAccountLast4s = setOf("3001"),
            debitCardScope = debitCardScope,
        )
        assertEquals(
            Money.zero(Currency.SAR),
            accountSummary.outflow.posPurchases,
        )
    }
}
