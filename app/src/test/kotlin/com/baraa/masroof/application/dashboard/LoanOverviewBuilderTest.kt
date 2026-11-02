package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class LoanOverviewBuilderTest {
    private val period = FinancialPeriod(
        startDate = LocalDate.parse("2026-08-27"),
        endDateExclusive = LocalDate.parse("2026-09-27"),
    )

    @Test
    fun build_includesRemainingBalanceAndPeriodPayment() {
        val loan = LoanRegistryEntry(
            id = "loan-1",
            bank = Bank.BANK_ALJAZIRA,
            loanType = LoanType.PERSONAL,
            ownership = OwnershipStatus.OWNED,
            displayName = "Personal loan",
            firstSeenRawSmsId = "sms-loan",
            lastSeenRawSmsId = "sms-loan",
        )
        val parsedRecords = listOf(
            ParsedEventRecord(
                event = ParsedEvent(
                    id = "evt-loan",
                    rawSmsId = "sms-loan",
                    bank = Bank.BANK_ALJAZIRA,
                    messageFamily = MessageFamily.FINANCING_INSTALLMENT,
                    direction = MoneyDirection.OUTGOING,
                    amount = Money.of("3036.11", Currency.SAR),
                    purchaseChannel = null,
                    cardRef = null,
                    sourceAccountRef = com.baraa.masroof.domain.model.AccountReference(
                        Bank.BANK_ALJAZIRA,
                        "3001",
                    ),
                    destinationAccountRef = null,
                    merchant = null,
                    counterparty = "تمويل شخصي",
                    occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
                    bankNetworkType = null,
                    confidence = Confidence(1.0),
                    parseStatus = ParseStatus.SUCCESS,
                ),
                details = ParsedEventDetails(
                    outstandingBalance = Money.of("33397.25", Currency.SAR),
                    loanType = LoanType.PERSONAL,
                ),
            ),
        )
        val loanContainerId = FinancialContainerIdFactory.loanId(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)
        val transactions = listOf(
            FinancialTransaction(
                id = TransactionIdFactory.fromRawSmsIds(listOf("sms-loan")),
                type = FinancialTransactionType.LOAN_REPAYMENT,
                amount = Money.of("3036.11", Currency.SAR),
                occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
                sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
                destinationContainerId = loanContainerId,
                merchant = null,
                counterparty = "تمويل شخصي",
                categoryId = null,
                linkedParsedEventIds = listOf("evt-loan"),
            ),
        )

        val overview = LoanOverviewBuilder.build(
            salaryPeriod = period,
            loans = listOf(loan),
            transactions = transactions,
            parsedRecords = parsedRecords,
            rawSmsById = mapOf("sms-loan" to rawSms("sms-loan", Instant.parse("2026-08-27T01:10:00Z"))),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = ZoneId.of("Asia/Riyadh"),
        )

        assertEquals(1, overview.loans.size)
        val personal = overview.loans.single()
        assertEquals(Money.of("33397.25", Currency.SAR), personal.remainingBalance)
        assertEquals(BigDecimal("3036.11"), personal.salaryPeriodPayment.amount)
    }

    @Test
    fun build_countsLegacyFeeFinancingInstallmentInPeriodPayment() {
        val loan = LoanRegistryEntry(
            id = "loan-1",
            bank = Bank.BANK_ALJAZIRA,
            loanType = LoanType.PERSONAL,
            ownership = OwnershipStatus.OWNED,
            displayName = "Personal loan",
            firstSeenRawSmsId = "sms-loan",
            lastSeenRawSmsId = "sms-loan",
        )
        val parsedRecords = listOf(
            ParsedEventRecord(
                event = ParsedEvent(
                    id = "evt-loan",
                    rawSmsId = "sms-loan",
                    bank = Bank.BANK_ALJAZIRA,
                    messageFamily = MessageFamily.FINANCING_INSTALLMENT,
                    direction = MoneyDirection.OUTGOING,
                    amount = Money.of("3036.11", Currency.SAR),
                    purchaseChannel = null,
                    cardRef = null,
                    sourceAccountRef = com.baraa.masroof.domain.model.AccountReference(
                        Bank.BANK_ALJAZIRA,
                        "3001",
                    ),
                    destinationAccountRef = null,
                    merchant = null,
                    counterparty = "تمويل شخصي",
                    occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
                    bankNetworkType = null,
                    confidence = Confidence(1.0),
                    parseStatus = ParseStatus.SUCCESS,
                ),
                details = ParsedEventDetails(loanType = LoanType.PERSONAL),
            ),
        )
        val transactions = listOf(
            FinancialTransaction(
                id = TransactionIdFactory.fromRawSmsIds(listOf("sms-loan")),
                type = FinancialTransactionType.FEE,
                amount = Money.of("3036.11", Currency.SAR),
                occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
                sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
                destinationContainerId = null,
                merchant = null,
                counterparty = "تمويل شخصي",
                categoryId = null,
                linkedParsedEventIds = listOf("evt-loan"),
            ),
        )

        val overview = LoanOverviewBuilder.build(
            salaryPeriod = period,
            loans = listOf(loan),
            transactions = transactions,
            parsedRecords = parsedRecords,
            rawSmsById = mapOf("sms-loan" to rawSms("sms-loan", Instant.parse("2026-08-27T01:10:00Z"))),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = ZoneId.of("Asia/Riyadh"),
        )

        assertEquals(BigDecimal("3036.11"), overview.loans.single().salaryPeriodPayment.amount)
    }

    @Test
    fun build_usesOccurredAtLocalWhenParsedEventOccurredAtNull() {
        val zoneId = ZoneId.of("Asia/Riyadh")
        val localTime = LocalDateTime.parse("2026-08-27T04:10:00")
        val loan = LoanRegistryEntry(
            id = "loan-1",
            bank = Bank.BANK_ALJAZIRA,
            loanType = LoanType.PERSONAL,
            ownership = OwnershipStatus.OWNED,
            displayName = "Personal loan",
            firstSeenRawSmsId = "sms-loan",
            lastSeenRawSmsId = "sms-loan",
        )
        val parsedRecords = listOf(
            ParsedEventRecord(
                event = ParsedEvent(
                    id = "evt-loan",
                    rawSmsId = "sms-loan",
                    bank = Bank.BANK_ALJAZIRA,
                    messageFamily = MessageFamily.FINANCING_INSTALLMENT,
                    direction = MoneyDirection.OUTGOING,
                    amount = Money.of("3036.11", Currency.SAR),
                    purchaseChannel = null,
                    cardRef = null,
                    sourceAccountRef = com.baraa.masroof.domain.model.AccountReference(
                        Bank.BANK_ALJAZIRA,
                        "3001",
                    ),
                    destinationAccountRef = null,
                    merchant = null,
                    counterparty = "تمويل شخصي",
                    occurredAt = null,
                    bankNetworkType = null,
                    confidence = Confidence(1.0),
                    parseStatus = ParseStatus.SUCCESS,
                ),
                details = ParsedEventDetails(
                    outstandingBalance = Money.of("33397.25", Currency.SAR),
                    occurredAtLocal = localTime,
                    loanType = LoanType.PERSONAL,
                ),
            ),
        )

        val overview = LoanOverviewBuilder.build(
            salaryPeriod = period,
            loans = listOf(loan),
            transactions = emptyList(),
            parsedRecords = parsedRecords,
            rawSmsById = mapOf("sms-loan" to rawSms("sms-loan", Instant.parse("2026-08-27T05:00:00Z"))),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zoneId,
        )

        val personal = overview.loans.single()
        assertEquals(Money.of("33397.25", Currency.SAR), personal.remainingBalance)
        assertEquals(localTime.atZone(zoneId).toInstant(), personal.remainingBalanceAsOf)
    }

    @Test
    fun build_usesRemainingBalanceAsOfSelectedSalaryPeriodEnd() {
        val previousPeriod = FinancialPeriod(
            startDate = LocalDate.parse("2026-07-27"),
            endDateExclusive = LocalDate.parse("2026-08-27"),
        )
        val loan = LoanRegistryEntry(
            id = "loan-1",
            bank = Bank.BANK_ALJAZIRA,
            loanType = LoanType.PERSONAL,
            ownership = OwnershipStatus.OWNED,
            displayName = "Personal loan",
            firstSeenRawSmsId = "sms-loan-prev",
            lastSeenRawSmsId = "sms-loan-current",
        )
        val parsedRecords = listOf(
            financingInstallmentRecord(
                eventId = "evt-loan-prev",
                rawSmsId = "sms-loan-prev",
                occurredAt = Instant.parse("2026-07-28T01:10:00Z"),
                outstandingBalance = Money.of("40000.00", Currency.SAR),
            ),
            financingInstallmentRecord(
                eventId = "evt-loan-current",
                rawSmsId = "sms-loan-current",
                occurredAt = Instant.parse("2026-08-28T01:10:00Z"),
                outstandingBalance = Money.of("33397.25", Currency.SAR),
            ),
        )
        val rawSmsById = mapOf(
            "sms-loan-prev" to rawSms("sms-loan-prev", Instant.parse("2026-07-28T01:10:00Z")),
            "sms-loan-current" to rawSms("sms-loan-current", Instant.parse("2026-08-28T01:10:00Z")),
        )

        val previousOverview = LoanOverviewBuilder.build(
            salaryPeriod = previousPeriod,
            loans = listOf(loan),
            transactions = emptyList(),
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = ZoneId.of("Asia/Riyadh"),
        )
        val currentOverview = LoanOverviewBuilder.build(
            salaryPeriod = period,
            loans = listOf(loan),
            transactions = emptyList(),
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = ZoneId.of("Asia/Riyadh"),
        )

        assertEquals(Money.of("40000.00", Currency.SAR), previousOverview.loans.single().remainingBalance)
        assertEquals(Money.of("33397.25", Currency.SAR), currentOverview.loans.single().remainingBalance)
    }

    @Test
    fun build_ignoresUnknownLoans() {
        val overview = LoanOverviewBuilder.build(
            salaryPeriod = period,
            loans = listOf(
                LoanRegistryEntry(
                    id = "loan-1",
                    bank = Bank.BANK_ALJAZIRA,
                    loanType = LoanType.PERSONAL,
                    ownership = OwnershipStatus.UNKNOWN,
                    displayName = null,
                    firstSeenRawSmsId = "sms-loan",
                    lastSeenRawSmsId = "sms-loan",
                ),
            ),
            transactions = emptyList(),
            parsedRecords = emptyList(),
            rawSmsById = emptyMap(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = ZoneId.of("Asia/Riyadh"),
        )

        assertEquals(0, overview.loans.size)
    }

    /** Screens localize an unnamed loan; the application layer must not hand them an English name to show. */
    @Test
    fun build_unnamedLoanHasNoCustomName_namedLoanKeepsIt() {
        fun owned(id: String, type: LoanType, name: String?) = LoanRegistryEntry(
            id = id, bank = Bank.BANK_ALJAZIRA, loanType = type, ownership = OwnershipStatus.OWNED,
            displayName = name, firstSeenRawSmsId = "sms-$id", lastSeenRawSmsId = "sms-$id",
        )
        val overview = LoanOverviewBuilder.build(
            salaryPeriod = period,
            loans = listOf(owned("a", LoanType.PERSONAL, null), owned("b", LoanType.AUTO, "  Car  ")),
            transactions = emptyList(),
            parsedRecords = emptyList(),
            rawSmsById = emptyMap(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = ZoneId.of("Asia/Riyadh"),
        )

        assertEquals(mapOf(LoanType.PERSONAL to null, LoanType.AUTO to "Car"), overview.loans.associate { it.loanType to it.customName })
    }

    private fun financingInstallmentRecord(
        eventId: String,
        rawSmsId: String,
        occurredAt: Instant,
        outstandingBalance: Money,
    ) = ParsedEventRecord(
        event = ParsedEvent(
            id = eventId,
            rawSmsId = rawSmsId,
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.FINANCING_INSTALLMENT,
            direction = MoneyDirection.OUTGOING,
            amount = Money.of("3036.11", Currency.SAR),
            purchaseChannel = null,
            cardRef = null,
            sourceAccountRef = com.baraa.masroof.domain.model.AccountReference(
                Bank.BANK_ALJAZIRA,
                "3001",
            ),
            destinationAccountRef = null,
            merchant = null,
            counterparty = "تمويل شخصي",
            occurredAt = occurredAt,
            bankNetworkType = null,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        ),
        details = ParsedEventDetails(
            outstandingBalance = outstandingBalance,
            loanType = LoanType.PERSONAL,
        ),
    )

    private fun rawSms(id: String, receivedAt: Instant) = RawSms(
        id = id,
        sender = "AlJazira",
        body = "loan sms",
        receivedAt = receivedAt,
        deviceMessageId = id,
        bodyHash = id,
    )
}
