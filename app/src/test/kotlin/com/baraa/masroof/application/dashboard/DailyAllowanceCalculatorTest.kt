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
    private fun net(amount: String) = SignedMoneyAmount(BigDecimal(amount), Currency.SAR)
    private fun dues(amount: String) = CommitmentsOverview.empty().copy(remaining = Money.of(amount, Currency.SAR))

    @Test fun whatIsLeftAfterUnpaidCommitmentsIsSpreadOverTheDaysToSalaryIncludingToday() {
        val result = DailyAllowanceCalculator.calculate(net("2148.61"), dues("373.32"), period, LocalDate.of(2026, 11, 5))!!
        assertEquals(21, result.days)
        assertEquals(Money.of("1775.29", Currency.SAR), Money.of(result.left.amount, Currency.SAR))
        assertEquals(Money.of("84.53", Currency.SAR), result.perDay) // Rounded down: never suggests more than is left.
        assertEquals(1, DailyAllowanceCalculator.calculate(net("100"), dues("0"), period, LocalDate.of(2026, 11, 25))!!.days)
    }

    @Test fun overspendingShowsZeroPerDayAndKeepsTheNegativeDifference() {
        val result = DailyAllowanceCalculator.calculate(net("200"), dues("500"), period, LocalDate.of(2026, 11, 5))!!
        assertEquals(Money.zero(Currency.SAR), result.perDay)
        assertTrue(result.left.isNegative)
    }

    @Test fun unknownFlowsAnotherCurrencyOrADayOutsideThePeriodGiveNoNumber() {
        assertNull(DailyAllowanceCalculator.calculate(null, dues("0"), period, LocalDate.of(2026, 11, 5)))
        assertNull(DailyAllowanceCalculator.calculate(SignedMoneyAmount(BigDecimal("10"), Currency.USD), dues("0"), period, LocalDate.of(2026, 11, 5)))
        assertNull(DailyAllowanceCalculator.calculate(net("10"), dues("0"), period, LocalDate.of(2026, 11, 26)))
    }
}
