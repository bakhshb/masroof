package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedAccountTest {
    @Test
    fun remaining_isExternalInMinusExternalOut() {
        val account = ownedAccount(
            salary = "10000",
            externalIn = "500",
            externalOut = "3000",
            selfIn = "5000",
            selfOut = "2000",
        )
        assertEquals(Money.of("10500.00", Currency.SAR), account.externalIn)
        assertEquals(Money.of("3000.00", Currency.SAR), account.externalOut)
        assertEquals(SignedMoneyAmount.of(Money.of("7500.00", Currency.SAR)), account.remaining)
        assertEquals(SignedMoneyAmount.of(Money.of("10500.00", Currency.SAR)), account.cashPosition)
    }

    @Test
    fun creditCardPayment_exposedSeparatelyFromPos() {
        val account = ownedAccount(
            cardPayment = "15000",
            pos = "90",
        )
        assertEquals(Money.of("15000.00", Currency.SAR), account.creditCardPayment)
        assertEquals(Money.of("90.00", Currency.SAR), account.posPurchase)
        assertEquals(Money.of("15090.00", Currency.SAR), account.externalOut)
    }

    @Test
    fun accountsSummary_sumsFleetTotals() {
        val a = ownedAccount(id = "3001", salary = "10000", externalOut = "1000")
        val b = ownedAccount(id = "3002", salary = "5000", externalOut = "500")
        val fleet = AccountsSummary(listOf(a, b))
        assertEquals(Money.of("15000.00", Currency.SAR), fleet.totalInflow)
        assertEquals(Money.of("1500.00", Currency.SAR), fleet.totalOutflow)
        assertEquals(SignedMoneyAmount.of(Money.of("13500.00", Currency.SAR)), fleet.totalRemaining)
        assertEquals(Money.of("0.00", Currency.SAR), fleet.totalCreditCardPayments())
    }

    @Test
    fun totalInflow_excludesTransferToAnotherOwnedAccount() {
        val source = ownedAccount(
            id = "3001",
            externalIn = "10000",
            selfOut = "10000",
        )
        val destination = ownedAccount(
            id = "3002",
            selfIn = "10000",
        )
        val fleet = AccountsSummary(listOf(source, destination))

        assertEquals(Money.of("10000.00", Currency.SAR), fleet.totalInflow)
        assertEquals(Money.of("0.00", Currency.SAR), fleet.totalOutflow)
        assertEquals(SignedMoneyAmount.of(Money.of("10000.00", Currency.SAR)), fleet.totalRemaining)
    }

    private fun ownedAccount(
        id: String = "3001",
        salary: String = "0",
        externalIn: String = "0",
        externalOut: String = "0",
        selfIn: String = "0",
        selfOut: String = "0",
        cardPayment: String = "0",
        pos: String = "0",
    ): OwnedAccount {
        val summary = CurrentAccountSummary.of(
            currency = Currency.SAR,
            salary = Money.of(salary, Currency.SAR),
            otherIncome = Money.zero(Currency.SAR),
            externalTransfersIn = Money.of(externalIn, Currency.SAR),
            selfTransfersIn = Money.of(selfIn, Currency.SAR),
            selfTransfersOut = Money.of(selfOut, Currency.SAR),
            creditCardPayments = Money.of(cardPayment, Currency.SAR),
            billPayments = Money.zero(Currency.SAR),
            externalTransfersOut = Money.of(externalOut, Currency.SAR),
            cashWithdrawals = Money.zero(Currency.SAR),
            posPurchases = Money.of(pos, Currency.SAR),
            fees = Money.zero(Currency.SAR),
            loanRepayments = Money.zero(Currency.SAR),
        )
        return OwnedAccount(
            id = id,
            bank = Bank.BANK_ALJAZIRA,
            maskedNumber = id,
            containerId = "account:bank_aljazira:$id",
            summary = summary,
        )
    }
}
