package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.LoanType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoanOverviewAggregatesTest {
    @Test
    fun aggregateRemainingBalance_unknownLoanDoesNotBecomeAPartialTotal() {
        val overview = LoansOverview(
            loans = listOf(
                loan(remaining = Money.of("100000.00", Currency.SAR), payment = "1500.00"),
                loan(remaining = null, payment = "2000.00"),
                loan(remaining = Money.of("25000.50", Currency.SAR), payment = "500.00"),
            ),
            salaryPeriodLabel = "27 August",
            currency = Currency.SAR,
        )

        assertNull(overview.aggregateRemainingBalance())
    }

    @Test
    fun aggregateRemainingBalance_nullWhenNoKnownBalances() {
        val overview = LoansOverview(
            loans = listOf(loan(remaining = null, payment = "1500.00")),
            salaryPeriodLabel = "27 August",
            currency = Currency.SAR,
        )

        assertNull(overview.aggregateRemainingBalance())
    }

    @Test
    fun aggregateRemainingBalance_neverCombinesCurrencies() {
        fun overview(vararg balances: Money) = LoansOverview(
            loans = balances.map { loan(remaining = it, payment = "0") },
            salaryPeriodLabel = null,
            currency = Currency.SAR,
        )

        assertNull(overview(Money.of("1000", Currency.SAR), Money.of("100", Currency.USD)).aggregateRemainingBalance())
        assertEquals(
            Money.of("1500", Currency.SAR),
            overview(Money.of("1000", Currency.SAR), Money.of("500", Currency.SAR)).aggregateRemainingBalance(),
        )
    }

    private fun loan(remaining: Money?, payment: String): LoanOverview =
        LoanOverview(
            bank = Bank.BANK_ALJAZIRA,
            loanType = LoanType.PERSONAL,
            displayLabel = "Personal loan",
            remainingBalance = remaining,
            remainingBalanceAsOf = null,
            salaryPeriodPayment = SignedMoneyAmount.of(Money.of(payment, Currency.SAR)),
            salaryPeriodLabel = "27 August",
        )
}
