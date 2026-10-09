package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money

/**
 * Canonical inflow breakdown for a current account in a salary period.
 *
 * [coreTotal] is used for aggregate net movement (excludes self-transfers).
 * [total] includes [selfTransfersIn] for a single-account remaining view.
 *
 * [accountRefunds] is cash credited back to this current account. It is not
 * salary and not ordinary income, and a credit-card refund never lands here.
 */
data class AccountInflow(
    val currency: Currency,
    val salary: Money,
    val otherIncome: Money,
    val externalTransfersIn: Money,
    val selfTransfersIn: Money,
    val accountRefunds: Money = Money.zero(currency),
) {
    val coreTotal: Money
        get() = salary + otherIncome + externalTransfersIn + accountRefunds

    val total: Money
        get() = coreTotal + selfTransfersIn

    init {
        require(salary.currency == currency)
        require(otherIncome.currency == currency)
        require(externalTransfersIn.currency == currency)
        require(selfTransfersIn.currency == currency)
        require(accountRefunds.currency == currency)
    }

    companion object {
        fun zero(currency: Currency): AccountInflow =
            AccountInflow(
                currency = currency,
                salary = Money.zero(currency),
                otherIncome = Money.zero(currency),
                externalTransfersIn = Money.zero(currency),
                selfTransfersIn = Money.zero(currency),
                accountRefunds = Money.zero(currency),
            )

        fun sum(summaries: Collection<AccountInflow>): AccountInflow {
            if (summaries.isEmpty()) return zero(Currency.SAR)
            return summaries.reduce { acc, next -> acc + next }
        }
    }

    operator fun plus(other: AccountInflow): AccountInflow {
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return AccountInflow(
            currency = currency,
            salary = salary + other.salary,
            otherIncome = otherIncome + other.otherIncome,
            externalTransfersIn = externalTransfersIn + other.externalTransfersIn,
            selfTransfersIn = selfTransfersIn + other.selfTransfersIn,
            accountRefunds = accountRefunds + other.accountRefunds,
        )
    }
}
