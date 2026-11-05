package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.period.FinancialPeriod
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DailyAllowanceCalculatorTest {
    private val period = FinancialPeriod(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 26))
    private val today = LocalDate.of(2026, 11, 5)
    private fun net(amount: String) = SignedMoneyAmount(BigDecimal(amount), Currency.SAR)
    private fun row(amount: String, date: LocalDate?, status: CommitmentPaymentStatus = CommitmentPaymentStatus.UNPAID,
        currency: Currency = Currency.SAR) =
        CommitmentDashboardRow("k$amount$date", CommitmentDashboardSource.USER, "Bill", Money.of(amount, currency), date, status)
    private fun dues(vararg rows: CommitmentDashboardRow) = CommitmentsOverview.empty().copy(rows = rows.toList())

    @Test fun whatIsLeftAfterUnpaidCommitmentsIsSpreadOverTheDaysToSalaryIncludingToday() {
        val result = DailyAllowanceCalculator.calculate(net("2148.61"), dues(row("176.35", today), row("196.97", null),
            row("50", LocalDate.of(2026, 11, 20), CommitmentPaymentStatus.PAID)), period, today)!!
        assertEquals(21, result.days)
        assertEquals(Money.of("373.32", Currency.SAR), result.unpaidCommitments)
        assertEquals(Money.of("1775.29", Currency.SAR), Money.of(result.left.amount, Currency.SAR))
        assertEquals(Money.of("84.53", Currency.SAR), result.perDay) // Rounded down: never suggests more than is left.
        assertEquals(1, DailyAllowanceCalculator.calculate(net("100"), dues(), period, LocalDate.of(2026, 11, 25))!!.days)
    }

    @Test fun aLateUnpaidRowIsStillOwed() {
        val result = DailyAllowanceCalculator.calculate(net("1000"), dues(row("500", LocalDate.of(2026, 11, 3))), period, today)!!
        assertEquals(Money.of("500", Currency.SAR), result.unpaidCommitments)
        assertEquals(BigDecimal("500.00"), result.left.amount)
    }

    @Test fun overspendingShowsZeroPerDayAndKeepsTheNegativeDifference() {
        val result = DailyAllowanceCalculator.calculate(net("200"), dues(row("500", LocalDate.of(2026, 11, 10))), period, today)!!
        assertEquals(Money.zero(Currency.SAR), result.perDay)
        assertTrue(result.left.isNegative)
    }

    @Test fun unknownFlowsAMixedCurrencyOrADayOutsideThePeriodGiveNoNumber() {
        assertNull(DailyAllowanceCalculator.calculate(null, dues(), period, today))
        assertNull(DailyAllowanceCalculator.calculate(net("10"), dues(row("5", today, currency = Currency.USD)), period, today))
        assertNull(DailyAllowanceCalculator.calculate(net("10"), dues(), period, LocalDate.of(2026, 11, 26)))
    }
}
