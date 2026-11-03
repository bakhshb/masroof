package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.RegistryEntityIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.CommitmentPauseInterval
import com.baraa.masroof.domain.model.CommitmentRecurrence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CommitmentsOverviewBuilderTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val period = FinancialPeriod(
        startDate = LocalDate.parse("2026-07-27"),
        endDateExclusive = LocalDate.parse("2026-08-27"),
    )

    @Test
    fun userCommitmentWithoutRecurrence_countsAsPaidInSourcePeriod() {
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val tx = transaction(
            id = "tx-netflix",
            merchant = "Netflix",
            amount = "71",
            at = start.plusSeconds(60),
        )
        val commitment = commitment(
            name = "Netflix",
            amount = Money.of("71.00", Currency.SAR),
            sourceTransactionId = tx.id,
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(tx),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(Money.of("71.00", Currency.SAR), overview.total)
        assertEquals(Money.of("71.00", Currency.SAR), overview.paid)
        assertEquals(Money.zero(Currency.SAR), overview.remaining)
        assertEquals(CommitmentPaymentStatus.PAID, overview.rows.single().status)
    }

    @Test
    fun oneTimeCommitment_outsideSalaryPeriod_isExcluded() {
        val previousPeriod = FinancialPeriod(
            startDate = LocalDate.parse("2026-06-27"),
            endDateExclusive = LocalDate.parse("2026-07-27"),
        )
        val commitment = commitment(
            name = "Netflix",
            amount = Money.of("71.00", Currency.SAR),
            sourceTransactionId = "tx-netflix",
            transactionDate = LocalDate.parse("2026-07-01"),
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertTrue(overview.rows.isEmpty())
        assertEquals(Money.zero(Currency.SAR), overview.total)

        val inPreviousPeriod = CommitmentsOverviewBuilder.build(
            salaryPeriod = previousPeriod,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )
        assertEquals(1, inPreviousPeriod.rows.size)
        assertEquals(CommitmentPaymentStatus.UNPAID, inPreviousPeriod.rows.single().status)
    }

    @Test
    fun oneTimeCommitment_editedTransactionDate_appearsOnlyInEditedPeriod() {
        val previousPeriod = FinancialPeriod(
            startDate = LocalDate.parse("2026-06-27"),
            endDateExclusive = LocalDate.parse("2026-07-27"),
        )
        val sourceTx = transaction(
            id = "tx-netflix",
            merchant = "Netflix",
            amount = "71",
            at = FinancialPeriodPolicy.toInclusiveStartInstant(previousPeriod.startDate, zone).plusSeconds(60),
        )
        val commitment = commitment(
            name = "Netflix",
            amount = Money.of("71.00", Currency.SAR),
            sourceTransactionId = sourceTx.id,
            transactionDate = LocalDate.parse("2026-08-01"),
        )

        val inSourcePeriod = CommitmentsOverviewBuilder.build(
            salaryPeriod = previousPeriod,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(sourceTx),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )
        assertTrue(inSourcePeriod.rows.isEmpty())

        val inEditedPeriod = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(sourceTx),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )
        assertEquals(1, inEditedPeriod.rows.size)
        assertEquals(CommitmentPaymentStatus.UNPAID, inEditedPeriod.rows.single().status)
    }

    @Test
    fun recurringCommitment_withoutPaymentInPeriod_isUnpaid() {
        val commitment = commitment(
            name = "STC",
            amount = Money.of("173.00", Currency.SAR),
            sourceTransactionId = "tx-old",
            recurrence = CommitmentRecurrence.MONTHLY,
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(Money.of("173.00", Currency.SAR), overview.total)
        assertEquals(Money.zero(Currency.SAR), overview.paid)
        assertEquals(Money.of("173.00", Currency.SAR), overview.remaining)
        assertEquals(CommitmentPaymentStatus.UNPAID, overview.rows.single().status)
    }

    @Test
    fun yearlyCommitment_outsideAnniversaryPeriod_isExcluded() {
        val commitment = commitment(
            name = "Insurance",
            amount = Money.of("1200.00", Currency.SAR),
            sourceTransactionId = "tx-insurance",
            recurrence = CommitmentRecurrence.YEARLY,
            transactionDate = LocalDate.parse("2026-01-15"),
        )

        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertTrue(overview.rows.isEmpty())
    }

    @Test
    fun weeklyCommitment_countsEachDueOccurrenceAndPayment() {
        val commitment = commitment(
            name = "Cleaning",
            amount = Money.of("100.00", Currency.SAR),
            sourceTransactionId = "tx-cleaning",
            recurrence = CommitmentRecurrence.WEEKLY,
            transactionDate = LocalDate.parse("2026-07-28"),
        )
        val payment = transaction(
            id = "payment-cleaning",
            merchant = "Cleaning",
            amount = "100",
            at = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone).plusSeconds(60),
        )

        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(payment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(5, overview.rows.size)
        assertEquals(Money.of("500.00", Currency.SAR), overview.total)
        assertEquals(Money.of("100.00", Currency.SAR), overview.paid)
        assertEquals(Money.of("400.00", Currency.SAR), overview.remaining)
        assertEquals(1, overview.rows.count { it.status == CommitmentPaymentStatus.PAID })
    }

    @Test
    fun weeklyCommitment_assignsPaidAmountsInPaymentChronologicalOrder() {
        val commitment = commitment(
            name = "Cleaning",
            amount = Money.of("100.00", Currency.SAR),
            sourceTransactionId = "tx-cleaning",
            recurrence = CommitmentRecurrence.WEEKLY,
            transactionDate = LocalDate.parse("2026-07-28"),
        )
        val earlierPayment = transaction(
            id = "payment-cleaning-earlier",
            merchant = "Cleaning",
            amount = "100",
            at = FinancialPeriodPolicy.toInclusiveStartInstant(LocalDate.parse("2026-07-28"), zone)
                .plusSeconds(60),
        )
        val laterPayment = transaction(
            id = "payment-cleaning-later",
            merchant = "Cleaning",
            amount = "120",
            at = FinancialPeriodPolicy.toInclusiveStartInstant(LocalDate.parse("2026-08-04"), zone)
                .plusSeconds(60),
        )

        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(laterPayment, earlierPayment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        val paidRows = overview.rows.filter { it.status == CommitmentPaymentStatus.PAID }
        assertEquals(2, paidRows.size)
        assertEquals(Money.of("100.00", Currency.SAR), paidRows[0].amount)
        assertEquals(Money.of("120.00", Currency.SAR), paidRows[1].amount)
        assertEquals(LocalDate.parse("2026-07-28"), paidRows[0].expectedDate)
        assertEquals(LocalDate.parse("2026-08-04"), paidRows[1].expectedDate)
    }

    @Test
    fun recurringCommitment_paidRowUsesPaymentDateNotOccurrence() {
        val paymentAt = FinancialPeriodPolicy.toInclusiveStartInstant(
            LocalDate.parse("2026-08-05"),
            zone,
        ).plusSeconds(60)
        val payment = transaction(
            id = "tx-stc-payment",
            merchant = "STC",
            amount = "165",
            at = paymentAt,
        )
        val commitment = commitment(
            name = "STC",
            amount = Money.of("200.00", Currency.SAR),
            sourceTransactionId = "tx-stc-original",
            recurrence = CommitmentRecurrence.MONTHLY,
            transactionDate = LocalDate.parse("2026-08-01"),
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(payment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(CommitmentPaymentStatus.PAID, overview.rows.single().status)
        assertEquals(Money.of("165.00", Currency.SAR), overview.rows.single().amount)
        assertEquals(LocalDate.parse("2026-08-05"), overview.rows.single().expectedDate)
    }

    @Test
    fun monthlyCommitment_withoutDueDate_usesOccurrenceAsExpectedDate() {
        val commitment = commitment(
            name = "Netflix",
            amount = Money.of("71.00", Currency.SAR),
            sourceTransactionId = "tx-netflix",
            recurrence = CommitmentRecurrence.MONTHLY,
            transactionDate = LocalDate.parse("2026-08-01"),
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(LocalDate.parse("2026-08-01"), overview.rows.single().expectedDate)
    }

    @Test
    fun monthlyCommitment_appearsInFollowingSalaryPeriod() {
        val commitment = commitment(
            name = "Netflix",
            amount = Money.of("71.00", Currency.SAR),
            sourceTransactionId = "tx-netflix",
            recurrence = CommitmentRecurrence.MONTHLY,
            transactionDate = LocalDate.parse("2026-08-01"),
        )
        val nextPeriod = FinancialPeriodPolicy.next(period)

        val inMarkedPeriod = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )
        assertEquals(1, inMarkedPeriod.rows.size)

        val inFollowingPeriod = CommitmentsOverviewBuilder.build(
            salaryPeriod = nextPeriod,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )
        assertEquals(1, inFollowingPeriod.rows.size)
        assertEquals(CommitmentPaymentStatus.UNPAID, inFollowingPeriod.rows.single().status)
    }

    @Test
    fun disabledCommitment_staysVisibleInEarlierPeriodsOnly() {
        val pausedAt = Instant.parse("2026-08-15T00:00:00Z")
        val commitment = commitment(
            name = "STC",
            amount = Money.of("173.00", Currency.SAR),
            sourceTransactionId = "tx-stc",
            recurrence = CommitmentRecurrence.MONTHLY,
            active = false,
            transactionDate = LocalDate.parse("2026-07-01"),
            pauseIntervals = listOf(CommitmentPauseInterval(pausedAt = pausedAt, resumedAt = null)),
        )
        val previousPeriod = FinancialPeriodPolicy.previous(period)

        val inPreviousPeriod = CommitmentsOverviewBuilder.build(
            salaryPeriod = previousPeriod,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )
        assertEquals(1, inPreviousPeriod.rows.size)

        val inDisabledPeriod = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )
        assertTrue(inDisabledPeriod.rows.isEmpty())
    }

    @Test
    fun refundDoesNotCountAsRecurringCommitmentPayment() {
        val refund = transaction(
            id = "refund-stc",
            merchant = "STC",
            amount = "173",
            at = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone).plusSeconds(60),
            type = FinancialTransactionType.REFUND,
        )
        val commitment = commitment(
            name = "STC",
            amount = Money.of("173.00", Currency.SAR),
            sourceTransactionId = "tx-stc",
            recurrence = CommitmentRecurrence.MONTHLY,
        )

        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(refund),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(CommitmentPaymentStatus.UNPAID, overview.rows.single().status)
    }

    @Test
    fun paidCreditCardStatement_isMarkedPaid() {
        val facility = creditFacility(
            due = Money.of("500.00", Currency.SAR),
            last4 = "1234",
        )
        val payment = transaction(
            id = "payment-card",
            merchant = "Card payment",
            amount = "500",
            at = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone).plusSeconds(60),
            type = FinancialTransactionType.CREDIT_CARD_PAYMENT,
            destinationContainerId = "card:${Bank.BANK_ALJAZIRA.id}:1234",
        )

        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = emptyList(),
            creditFacilities = CreditFacilitiesOverview(listOf(facility), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(payment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(CommitmentPaymentStatus.PAID, overview.rows.single().status)
        assertEquals(Money.of("500.00", Currency.SAR), overview.paid)
        assertEquals(Money.zero(Currency.SAR), overview.remaining)
    }

    @Test
    fun cardsAndCommitments_agreeOnPaidAndUnpaidFacilityDues() {
        val paidFacility = creditFacility(due = Money.of("500.00", Currency.SAR), last4 = "7271")
        val unpaidFacility = creditFacility(due = Money.of("300.00", Currency.SAR), last4 = "1234")
        val payment = transaction(
            id = "payment-7271",
            merchant = "Card payment",
            amount = "500",
            at = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone).plusSeconds(60),
            type = FinancialTransactionType.CREDIT_CARD_PAYMENT,
            destinationContainerId = "card:${Bank.BANK_ALJAZIRA.id}:7271",
        )
        val facilities = CreditFacilitiesOverview(listOf(paidFacility, unpaidFacility), emptyList(), Currency.SAR)

        val commitments = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = emptyList(),
            creditFacilities = facilities,
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(payment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )
        // The Cards screen reads the facilities after this same step in DashboardProjectionBuilder.
        val cards = facilities.withPaidDues(commitments.paidCreditFacilityKeys)

        val statusByKey = commitments.rows.associate { it.creditFacilityKey to it.status }
        cards.facilities.forEach { facility ->
            assertEquals(statusByKey[facility.facilityKey] == CommitmentPaymentStatus.PAID, facility.facilityDuePaid)
        }
        assertEquals(CommitmentPaymentStatus.PAID, statusByKey[paidFacility.facilityKey])
        assertEquals(CommitmentPaymentStatus.UNPAID, statusByKey[unpaidFacility.facilityKey])
        assertTrue(!cards.allFacilityDuesPaid)
        assertTrue(cards.copy(facilities = cards.facilities.take(1)).allFacilityDuesPaid)
    }

    @Test
    fun paidCreditCardStatement_doesNotReappearUnpaidInLaterSalaryPeriod() {
        val facility = creditFacility(
            due = Money.of("500.00", Currency.SAR),
            last4 = "1234",
        )
        val payment = transaction(
            id = "payment-card",
            merchant = "Card payment",
            amount = "500",
            at = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone).plusSeconds(60),
            type = FinancialTransactionType.CREDIT_CARD_PAYMENT,
            destinationContainerId = "card:${Bank.BANK_ALJAZIRA.id}:1234",
        )
        val laterPeriod = FinancialPeriod(
            startDate = LocalDate.parse("2026-08-27"),
            endDateExclusive = LocalDate.parse("2026-09-27"),
        )

        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = laterPeriod,
            commitments = emptyList(),
            creditFacilities = CreditFacilitiesOverview(listOf(facility), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(payment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertTrue(overview.rows.isEmpty())
        assertEquals(Money.zero(Currency.SAR), overview.total)
        assertEquals(Money.zero(Currency.SAR), overview.remaining)
    }

    @Test
    fun paidCreditCardStatement_countsPaymentBeforeSalaryPeriodStart() {
        val facility = creditFacility(
            due = Money.of("500.00", Currency.SAR),
            last4 = "1234",
        )
        val payment = transaction(
            id = "payment-card",
            merchant = "Card payment",
            amount = "500",
            at = Instant.parse("2026-07-26T12:00:00Z"),
            type = FinancialTransactionType.CREDIT_CARD_PAYMENT,
            destinationContainerId = "card:${Bank.BANK_ALJAZIRA.id}:1234",
        )

        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = emptyList(),
            creditFacilities = CreditFacilitiesOverview(listOf(facility), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(payment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(CommitmentPaymentStatus.PAID, overview.rows.single().status)
    }

    @Test
    fun duplicateRecurringCommitments_shareSingleInPeriodPayment() {
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val payment = transaction(
            id = "payment-netflix",
            merchant = "Netflix",
            amount = "71",
            at = start.plusSeconds(120),
        )
        val first = commitment(
            id = "cmt_first",
            name = "Netflix",
            amount = Money.of("71.00", Currency.SAR),
            sourceTransactionId = "tx-netflix-1",
            recurrence = CommitmentRecurrence.MONTHLY,
        )
        val second = commitment(
            id = "cmt_second",
            name = "Netflix",
            amount = Money.of("71.00", Currency.SAR),
            sourceTransactionId = "tx-netflix-2",
            recurrence = CommitmentRecurrence.MONTHLY,
        )

        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(first, second),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(payment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(2, overview.rows.size)
        assertEquals(0, overview.rows.count { it.status == CommitmentPaymentStatus.PAID })
        assertEquals(2, overview.rows.count { it.status == CommitmentPaymentStatus.UNPAID })
        assertEquals(Money.zero(Currency.SAR), overview.paid)
        assertEquals(Money.of("142.00", Currency.SAR), overview.remaining)
        assertTrue(overview.rows.all { it.paymentMatchIssue == CommitmentMatchIssue.AMBIGUOUS_PAYMENT })
    }

    @Test
    fun inactiveCommitments_areExcluded() {
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(
                commitment(
                    name = "School",
                    amount = Money.of("3000.00", Currency.SAR),
                    sourceTransactionId = "tx-school",
                    active = false,
                    pauseIntervals = listOf(
                        CommitmentPauseInterval(
                            pausedAt = Instant.parse("2026-08-01T00:00:00Z"),
                            resumedAt = null,
                        ),
                    ),
                ),
            ),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertTrue(overview.rows.isEmpty())
        assertEquals(Money.zero(Currency.SAR), overview.total)
    }

    @Test
    fun loanWithPeriodPayment_countsAsPaid() {
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = emptyList(),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(
                loans = listOf(
                    LoanOverview(
                        bank = Bank.BANK_ALJAZIRA,
                        loanType = LoanType.PERSONAL,
                        displayLabel = "Personal loan",
                        remainingBalance = Money.of("12000.00", Currency.SAR),
                        remainingBalanceAsOf = Instant.parse("2026-08-01T00:00:00Z"),
                        salaryPeriodPayment = SignedMoneyAmount.of(Money.of("900.00", Currency.SAR)),
                        salaryPeriodLabel = "27 July",
                    ),
                ),
                salaryPeriodLabel = "27 July",
                currency = Currency.SAR,
            ),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(CommitmentPaymentStatus.PAID, overview.rows.single().status)
        assertEquals(Money.of("900.00", Currency.SAR), overview.total)
        assertEquals(Money.of("900.00", Currency.SAR), overview.paid)
        assertEquals(Money.zero(Currency.SAR), overview.remaining)
    }

    @Test
    fun loanWithoutPeriodPayment_usesInstallmentNotRemainingBalance() {
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = emptyList(),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(
                loans = listOf(
                    LoanOverview(
                        bank = Bank.BANK_ALJAZIRA,
                        loanType = LoanType.PERSONAL,
                        displayLabel = "Personal loan",
                        remainingBalance = Money.of("12000.00", Currency.SAR),
                        remainingBalanceAsOf = Instant.parse("2026-08-01T00:00:00Z"),
                        latestInstallmentAmount = Money.of("900.00", Currency.SAR),
                        latestInstallmentAsOf = Instant.parse("2026-07-27T00:00:00Z"),
                        salaryPeriodPayment = SignedMoneyAmount.zero(Currency.SAR),
                        salaryPeriodLabel = "27 July",
                    ),
                ),
                salaryPeriodLabel = "27 July",
                currency = Currency.SAR,
            ),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(CommitmentPaymentStatus.UNPAID, overview.rows.single().status)
        assertEquals(Money.of("900.00", Currency.SAR), overview.total)
        assertEquals(Money.of("900.00", Currency.SAR), overview.remaining)
    }

    @Test
    fun paidOffLoanWithoutPeriodPayment_isOmitted() {
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = emptyList(),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(
                loans = listOf(
                    LoanOverview(
                        bank = Bank.BANK_ALJAZIRA,
                        loanType = LoanType.PERSONAL,
                        displayLabel = "Personal loan",
                        remainingBalance = Money.zero(Currency.SAR),
                        remainingBalanceAsOf = Instant.parse("2026-08-01T00:00:00Z"),
                        latestInstallmentAmount = Money.of("900.00", Currency.SAR),
                        latestInstallmentAsOf = Instant.parse("2026-07-27T00:00:00Z"),
                        salaryPeriodPayment = SignedMoneyAmount.zero(Currency.SAR),
                        salaryPeriodLabel = "27 July",
                    ),
                ),
                salaryPeriodLabel = "27 July",
                currency = Currency.SAR,
            ),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertTrue(overview.rows.isEmpty())
        assertEquals(Money.zero(Currency.SAR), overview.total)
    }

    @Test
    fun paidCommitment_usesActualPaymentAmountNotStoredCommitmentAmount() {
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val tx = transaction(
            id = "tx-stc",
            merchant = "STC",
            amount = "173",
            at = start.plusSeconds(60),
        )
        val commitment = commitment(
            name = "STC",
            amount = Money.of("200.00", Currency.SAR),
            sourceTransactionId = tx.id,
            recurrence = CommitmentRecurrence.MONTHLY,
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(tx),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(Money.of("173.00", Currency.SAR), overview.rows.single().amount)
        assertEquals(Money.of("173.00", Currency.SAR), overview.total)
        assertEquals(Money.of("173.00", Currency.SAR), overview.paid)
        assertEquals(Money.zero(Currency.SAR), overview.remaining)
        assertEquals(CommitmentPaymentStatus.PAID, overview.rows.single().status)
    }

    @Test
    fun recurringCommitment_marksPaidWhenPaymentAmountDiffersFromCommitmentAmount() {
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val payment = transaction(
            id = "tx-stc-payment",
            merchant = "STC",
            amount = "165",
            at = start.plusSeconds(120),
        )
        val commitment = commitment(
            name = "STC",
            amount = Money.of("200.00", Currency.SAR),
            sourceTransactionId = "tx-stc-original",
            recurrence = CommitmentRecurrence.MONTHLY,
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(payment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(Money.of("165.00", Currency.SAR), overview.rows.single().amount)
        assertEquals(Money.of("165.00", Currency.SAR), overview.total)
        assertEquals(Money.of("165.00", Currency.SAR), overview.paid)
        assertEquals(Money.zero(Currency.SAR), overview.remaining)
        assertEquals(CommitmentPaymentStatus.PAID, overview.rows.single().status)
    }

    @Test
    fun editedCommitmentAmount_marksPaidWhenSourceTransactionMatchesEditedAmount() {
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val tx = transaction(
            id = "tx-stc",
            merchant = "STC",
            amount = "200",
            at = start.plusSeconds(60),
        )
        val commitment = commitment(
            name = "STC",
            amount = Money.of("200.00", Currency.SAR),
            sourceTransactionId = tx.id,
            recurrence = CommitmentRecurrence.MONTHLY,
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(tx),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(CommitmentPaymentStatus.PAID, overview.rows.single().status)
    }

    @Test
    fun foreignCurrencyCommitment_usesSarEquivalentWhenSourceTxMissingFromPeriodList() {
        val commitment = commitment(
            name = "Amazon",
            amount = Money.of("25.00", Currency.USD),
            sourceTransactionId = "tx-usd",
            recurrence = CommitmentRecurrence.MONTHLY,
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = mapOf("tx-usd" to Money.of("93.75", Currency.SAR)),
            zoneId = zone,
        )

        assertEquals(Money.of("93.75", Currency.SAR), overview.rows.single().amount)
        assertEquals(Money.of("93.75", Currency.SAR), overview.total)
    }

    @Test
    fun foreignCurrencyCommitment_matchesLaterPaymentUsingEditedAmount() {
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val sourceTx = FinancialTransaction(
            id = "tx-usd",
            type = FinancialTransactionType.EXPENSE,
            amount = Money.of("25.00", Currency.USD),
            occurredAt = start.minusSeconds(3600),
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = "Amazon",
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = emptyList(),
        )
        val paymentTx = transaction(
            id = "tx-payment",
            merchant = "Amazon",
            amount = "112.50",
            at = start.plusSeconds(120),
        )
        val commitment = commitment(
            name = "Amazon",
            amount = Money.of("30.00", Currency.USD),
            sourceTransactionId = sourceTx.id,
            recurrence = CommitmentRecurrence.MONTHLY,
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(commitment),
            creditFacilities = CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = Currency.SAR,
            ),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(sourceTx, paymentTx),
            primaryCurrency = Currency.SAR,
            sarEquivalents = mapOf(sourceTx.id to Money.of("93.75", Currency.SAR)),
            zoneId = zone,
        )

        assertEquals(CommitmentPaymentStatus.PAID, overview.rows.single().status)
        assertEquals(Money.of("112.50", Currency.SAR), overview.rows.single().amount)
    }

    @Test
    fun monthlyCommitment_countsEveryPaymentInSalaryPeriodWhenAmountIncreases() {
        val salaryPeriod = FinancialPeriod(
            startDate = LocalDate.parse("2026-09-27"),
            endDateExclusive = LocalDate.parse("2026-10-27"),
        )
        val septemberPayment = transaction(
            id = "chatgpt-september",
            merchant = "Google ChatGPT",
            amount = "267.93",
            at = Instant.parse("2026-09-29T18:21:00Z"),
        )
        val octoberPayment = transaction(
            id = "chatgpt-october",
            merchant = "Google ChatGPT",
            amount = "429.99",
            at = Instant.parse("2026-10-21T18:21:00Z"),
        )
        val beforePeriod = septemberPayment.copy(
            id = "before-period",
            occurredAt = FinancialPeriodPolicy.toInclusiveStartInstant(salaryPeriod.startDate, zone).minusSeconds(1),
        )
        val afterPeriod = octoberPayment.copy(
            id = "after-period",
            occurredAt = FinancialPeriodPolicy.toExclusiveEndInstant(salaryPeriod.endDateExclusive, zone),
        )
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = salaryPeriod,
            commitments = listOf(
                commitment(
                    name = "Google ChatGPT",
                    amount = septemberPayment.amount,
                    sourceTransactionId = septemberPayment.id,
                    recurrence = CommitmentRecurrence.MONTHLY,
                    transactionDate = LocalDate.parse("2026-09-29"),
                ),
            ),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(afterPeriod, octoberPayment, beforePeriod, septemberPayment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(listOf(septemberPayment.id, octoberPayment.id), overview.rows.map { it.paymentTransactionId })
        assertEquals(listOf(septemberPayment.amount, octoberPayment.amount), overview.rows.map { it.amount })
        assertEquals(
            listOf(LocalDate.parse("2026-09-29"), LocalDate.parse("2026-10-21")),
            overview.rows.map { it.expectedDate },
        )
        assertTrue(overview.rows.all { it.status == CommitmentPaymentStatus.PAID })
        assertEquals(Money.of("697.92", Currency.SAR), overview.total)
        assertEquals(overview.total, overview.paid)
        assertEquals(Money.zero(Currency.SAR), overview.remaining)
    }

    @Test
    fun recurringCommitment_keepsSeparatePaymentsOnSameDayWithUniqueKeys() {
        val firstPayment = transaction(
            id = "first-payment",
            merchant = "Subscription",
            amount = "100",
            at = Instant.parse("2026-08-01T10:00:00Z"),
        )
        val secondPayment = firstPayment.copy(id = "second-payment", amount = Money.of("150", Currency.SAR))
        val overview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = listOf(
                commitment(
                    name = "Subscription",
                    amount = firstPayment.amount,
                    sourceTransactionId = firstPayment.id,
                    recurrence = CommitmentRecurrence.MONTHLY,
                ),
            ),
            creditFacilities = CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
            loansOverview = LoansOverview(emptyList(), null, Currency.SAR),
            transactions = listOf(firstPayment, secondPayment),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
        )

        assertEquals(2, overview.rows.size)
        assertEquals(2, overview.rows.map { it.key }.distinct().size)
        assertEquals(Money.of("250", Currency.SAR), overview.paid)
        assertEquals(Money.zero(Currency.SAR), overview.remaining)
    }

    private fun commitment(
        name: String,
        amount: Money,
        sourceTransactionId: String,
        recurrence: CommitmentRecurrence? = null,
        active: Boolean = true,
        transactionDate: LocalDate = LocalDate.parse("2026-08-01"),
        id: String = RegistryEntityIdFactory.newCommitmentId(),
        updatedAt: Instant = Instant.parse("2026-08-01T00:00:00Z"),
        pauseIntervals: List<CommitmentPauseInterval> = emptyList(),
    ): Commitment {
        val now = Instant.parse("2026-08-01T00:00:00Z")
        return Commitment(
            id = id,
            name = name,
            amount = amount,
            transactionDate = transactionDate,
            recurrence = recurrence,
            dueDate = null,
            active = active,
            pauseIntervals = pauseIntervals,
            sourceTransactionId = sourceTransactionId,
            createdAt = now,
            updatedAt = updatedAt,
        )
    }

    private fun transaction(
        id: String,
        merchant: String,
        amount: String,
        at: Instant,
        type: FinancialTransactionType = FinancialTransactionType.EXPENSE,
        destinationContainerId: String? = null,
    ): FinancialTransaction =
        FinancialTransaction(
            id = id,
            type = type,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = at,
            sourceContainerId = null,
            destinationContainerId = destinationContainerId,
            merchant = merchant,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = emptyList(),
        )

    private fun creditFacility(
        due: Money,
        last4: String,
    ): CreditFacilityOverview =
        CreditFacilityOverview(
            bank = Bank.BANK_ALJAZIRA,
            primary = CreditCardDashboardRow(
                bank = Bank.BANK_ALJAZIRA,
                last4 = last4,
                statementSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
                salaryPeriodSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
                statementPeriodLabel = null,
                snapshot = null,
            ),
            supplementaries = emptyList(),
            facilityDue = StatementDueSnapshot(
                amount = due,
                updatedAt = Instant.parse("2026-07-25T00:00:00Z"),
                dueDate = LocalDate.parse("2026-08-15"),
            ),
            facilitySalaryPeriodSpending = SignedMoneyAmount.zero(Currency.SAR),
            facilityStatementSpending = SignedMoneyAmount.zero(Currency.SAR),
            aggregateStatementPeriodLabel = null,
            salaryPeriodLabel = null,
            currency = Currency.SAR,
        )

    @Test
    fun upcomingUnpaid_ordersByNearestDueDateWithUndatedLast() {
        fun row(name: String, date: String?, status: CommitmentPaymentStatus = CommitmentPaymentStatus.UNPAID) =
            CommitmentDashboardRow(name, CommitmentDashboardSource.USER, name, Money.of("10.00", Currency.SAR),
                date?.let(LocalDate::parse), status)
        // Rows arrive in name order, as the builder sorts them.
        val overview = CommitmentsOverview(
            rows = listOf(row("ChatGPT", "2026-11-21"), row("Gym", null), row("Rent", "2026-11-01", CommitmentPaymentStatus.PAID),
                row("YouTube", "2026-11-06")),
            total = Money.of("40.00", Currency.SAR),
            paid = Money.of("10.00", Currency.SAR),
            remaining = Money.of("30.00", Currency.SAR),
            currency = Currency.SAR,
        )

        assertEquals(listOf("YouTube", "ChatGPT", "Gym"), overview.upcomingUnpaid.map { it.displayName })
    }
}
