package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class SpendingAmountsTest {
    @Test
    fun creditFacilitiesOverview_separatesCreditAndDebitAggregates() {
        val overview = CreditFacilitiesOverview(
            facilities = listOf(
                facilitySpending("100.00", "80.00"),
                facilitySpending("50.00", "40.00"),
            ),
            debitCards = listOf(
                debitSpending("120.00"),
                debitSpending("30.00"),
            ),
            currency = Currency.SAR,
        )

        assertEquals(
            BigDecimal("150.00"),
            overview.aggregateCreditSalaryPeriodSpending().amount,
        )
        assertEquals(
            BigDecimal("120.00"),
            overview.aggregateCreditStatementSpending().amount,
        )
        assertEquals(
            BigDecimal("150.00"),
            overview.aggregateDebitSalaryPeriodSpending().amount,
        )
    }

    @Test
    fun totalFacilityDue_countsEachFacilityOnceIncludingItsSupplementaries() {
        val due = StatementDueSnapshot(Money.of("100", Currency.SAR), Instant.parse("2026-08-01T09:00:00Z"), LocalDate.parse("2026-09-01"))
        val first = facilitySpending("0", "0", due)
        val second = first.copy(primary = first.primary.copy(last4 = "2222"),
            facilityDue = due.copy(amount = Money.of("250", Currency.SAR)))
        val withSupplementary = first.copy(supplementaries = listOf(first.primary.copy(last4 = "3333")))
        val overview = CreditFacilitiesOverview(listOf(withSupplementary, second, first), emptyList(), Currency.SAR)
        assertEquals(Money.of("350", Currency.SAR), overview.totalFacilityDue)
    }

    @Test
    fun unpaidFacilityDue_leavesOutPaidFacilities() {
        val due = StatementDueSnapshot(Money.of("100", Currency.SAR), Instant.parse("2026-08-01T09:00:00Z"), LocalDate.parse("2026-09-01"))
        val first = facilitySpending("0", "0", due)
        val second = first.copy(primary = first.primary.copy(last4 = "2222"),
            facilityDue = due.copy(amount = Money.of("250", Currency.SAR)))
        val overview = CreditFacilitiesOverview(listOf(first, second), emptyList(), Currency.SAR)
        assertEquals(Money.of("350", Currency.SAR), overview.unpaidFacilityDue)
        val onePaid = overview.withPaidDues(setOf(first.facilityKey))
        assertEquals(Money.of("350", Currency.SAR), onePaid.totalFacilityDue)
        assertEquals(Money.of("250", Currency.SAR), onePaid.unpaidFacilityDue)
        assertEquals(Money.of("0", Currency.SAR), overview.withPaidDues(setOf(first.facilityKey, second.facilityKey)).unpaidFacilityDue)
    }

    @Test
    fun unpaidFacilityDue_ignoresAPaidForeignCurrencyFacility() {
        val due = StatementDueSnapshot(Money.of("300", Currency.SAR), Instant.parse("2026-08-01T09:00:00Z"), LocalDate.parse("2026-09-01"))
        val sar = facilitySpending("0", "0", due)
        val usd = sar.copy(primary = sar.primary.copy(last4 = "2222"), facilityDue = due.copy(amount = Money.of("80", Currency.USD)))
        val overview = CreditFacilitiesOverview(listOf(sar, usd), emptyList(), Currency.SAR).withPaidDues(setOf(usd.facilityKey))
        assertEquals(null, overview.totalFacilityDue)
        assertEquals(Money.of("300", Currency.SAR), overview.unpaidFacilityDue)
    }

    @Test
    fun totalFacilityDue_doesNotInventUnknownOrMixedCurrencyDues() {
        val due = StatementDueSnapshot(Money.of("100", Currency.SAR), Instant.parse("2026-08-01T09:00:00Z"), null)
        val first = facilitySpending("0", "0", due)
        val unknown = first.copy(primary = first.primary.copy(last4 = "2222"), facilityDue = null)
        assertNull(CreditFacilitiesOverview(listOf(first, unknown), emptyList(), Currency.SAR).totalFacilityDue)
        val foreign = unknown.copy(facilityDue = due.copy(amount = Money.of("50", Currency.USD)))
        assertNull(CreditFacilitiesOverview(listOf(first, foreign), emptyList(), Currency.SAR).totalFacilityDue)
    }

    private fun facilitySpending(
        salary: String,
        statement: String,
        due: StatementDueSnapshot? = null,
    ): CreditFacilityOverview {
        val row = CreditCardDashboardRow(
            bank = com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
            last4 = "1111",
            statementSpendingNet = SignedMoneyAmount.of(Money.of(statement, Currency.SAR)),
            salaryPeriodSpendingNet = SignedMoneyAmount.of(Money.of(salary, Currency.SAR)),
            statementPeriodLabel = null,
            snapshot = null,
        )
        return CreditFacilityOverview(
            bank = row.bank,
            primary = row,
            supplementaries = emptyList(),
            facilityDue = due,
            facilitySalaryPeriodSpending = SignedMoneyAmount.of(Money.of(salary, Currency.SAR)),
            facilityStatementSpending = SignedMoneyAmount.of(Money.of(statement, Currency.SAR)),
            aggregateStatementPeriodLabel = null,
            salaryPeriodLabel = "Aug",
            currency = Currency.SAR,
        )
    }

    private fun debitSpending(amount: String): DebitCardOverview =
        DebitCardOverview(
            bank = com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
            last4 = "2210",
            displayLabel = "Mada ••2210",
            linkedAccountLabel = null,
            linkedAccountMaskedNumber = null,
            network = com.baraa.masroof.domain.model.CardNetwork.MADA,
            salaryPeriodSpendingNet = SignedMoneyAmount.of(Money.of(amount, Currency.SAR)),
            salaryPeriodLabel = "Aug",
        )
}
