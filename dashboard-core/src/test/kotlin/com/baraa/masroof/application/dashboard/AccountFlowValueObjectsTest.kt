package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AccountFlowValueObjectsTest {
    @Test
    fun accountInflow_coreTotal_sumsStandardCategories() {
        val currency = Currency.SAR
        val inflow = AccountInflow(
            currency = currency,
            salary = Money.of("3000", currency),
            otherIncome = Money.of("100", currency),
            externalTransfersIn = Money.of("500", currency),
            selfTransfersIn = Money.of("200", currency),
        )
        assertEquals(Money.of("3600.00", currency), inflow.coreTotal)
        assertEquals(Money.of("3800.00", currency), inflow.total)
    }

    @Test
    fun accountOutflow_coreTotal_sumsStandardCategories() {
        val currency = Currency.SAR
        val outflow = AccountOutflow(
            currency = currency,
            externalTransfersOut = Money.of("100", currency),
            creditCardPayments = Money.of("50", currency),
            cashWithdrawals = Money.of("30", currency),
            billPayments = Money.of("40", currency),
            posPurchases = Money.of("60", currency),
            fees = Money.of("10", currency),
            loanRepayments = Money.of("25", currency),
            selfTransfersOut = Money.of("200", currency),
        )
        assertEquals(Money.of("315.00", currency), outflow.coreTotal)
        assertEquals(Money.of("515.00", currency), outflow.total)
    }

    @Test
    fun aggregate_sumsPerAccountSummaries() {
        val currency = Currency.SAR
        val accountA = CurrentAccountSummary.of(
            currency = currency,
            salary = Money.of("1000", currency),
            otherIncome = Money.zero(currency),
            externalTransfersIn = Money.zero(currency),
            selfTransfersIn = Money.zero(currency),
            creditCardPayments = Money.zero(currency),
            billPayments = Money.of("200", currency),
            externalTransfersOut = Money.zero(currency),
            cashWithdrawals = Money.zero(currency),
            posPurchases = Money.zero(currency),
            fees = Money.zero(currency),
            selfTransfersOut = Money.of("300", currency),
        )
        val accountB = CurrentAccountSummary.of(
            currency = currency,
            salary = Money.zero(currency),
            otherIncome = Money.zero(currency),
            externalTransfersIn = Money.zero(currency),
            selfTransfersIn = Money.of("300", currency),
            selfTransfersOut = Money.zero(currency),
            creditCardPayments = Money.zero(currency),
            billPayments = Money.zero(currency),
            externalTransfersOut = Money.zero(currency),
            cashWithdrawals = Money.zero(currency),
            posPurchases = Money.of("50", currency),
            fees = Money.zero(currency),
        )
        val aggregate = CurrentAccountSummary.aggregate(listOf(accountA, accountB))
        assertEquals(Money.of("1000.00", currency), aggregate.inflow.coreTotal)
        assertEquals(Money.of("1300.00", currency), aggregate.inflow.total)
        assertEquals(Money.of("250.00", currency), aggregate.outflow.coreTotal)
        assertEquals(Money.of("550.00", currency), aggregate.outflow.total)
        assertEquals(
            SignedMoneyAmount.of(Money.of("750.00", currency)),
            aggregate.externalMovement().remaining,
        )
        assertEquals(
            SignedMoneyAmount.of(Money.of("750.00", currency)),
            aggregate.cashPosition().remaining,
        )
    }
}
