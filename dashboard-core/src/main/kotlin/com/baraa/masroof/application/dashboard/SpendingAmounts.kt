package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import java.math.BigDecimal
import java.math.RoundingMode

object SpendingAmounts {
    fun sum(amounts: List<SignedMoneyAmount>): SignedMoneyAmount {
        if (amounts.isEmpty()) return SignedMoneyAmount.zero(Currency.SAR)
        var sum = BigDecimal.ZERO
        val currency = amounts.first().currency
        amounts.forEach { sum = sum.add(it.amount) }
        return SignedMoneyAmount(
            sum.setScale(Money.SCALE, RoundingMode.HALF_EVEN),
            currency,
        )
    }
}

fun CreditFacilitiesOverview.aggregateCreditSalaryPeriodSpending(): SignedMoneyAmount =
    SpendingAmounts.sum(facilities.map { it.facilitySalaryPeriodSpending })

fun CreditFacilitiesOverview.aggregateCreditStatementSpending(): SignedMoneyAmount =
    SpendingAmounts.sum(facilities.map { it.facilityStatementSpending })

fun CreditFacilitiesOverview.aggregateDebitSalaryPeriodSpending(): SignedMoneyAmount =
    SpendingAmounts.sum(debitCards.map { it.salaryPeriodSpendingNet })
