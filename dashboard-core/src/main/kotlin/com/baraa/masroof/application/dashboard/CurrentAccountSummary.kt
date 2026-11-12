package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money

/**
 * Cash movement on owned current-account containers for a salary period.
 *
 * Inflow and outflow categories are defined by [AccountInflow] and [AccountOutflow].
 * Credit-card purchases on the card itself are excluded — they are tracked on the card section.
 */
data class CurrentAccountSummary(
    val inflow: AccountInflow,
    val outflow: AccountOutflow,
) {
    val currency: Currency get() = inflow.currency

    /** False when nothing moved in or out of the account this period. */
    val hasActivity: Boolean get() = inflow.total.amount.signum() != 0 || outflow.total.amount.signum() != 0

    init {
        require(inflow.currency == outflow.currency)
    }

    companion object {
        fun of(
            currency: Currency,
            salary: Money,
            otherIncome: Money,
            externalTransfersIn: Money,
            selfTransfersIn: Money,
            creditCardPayments: Money,
            billPayments: Money,
            externalTransfersOut: Money,
            cashWithdrawals: Money,
            posPurchases: Money,
            fees: Money,
            loanRepayments: Money = Money.zero(currency),
            selfTransfersOut: Money,
            accountRefunds: Money = Money.zero(currency),
        ): CurrentAccountSummary =
            CurrentAccountSummary(
                inflow = AccountInflow(
                    currency = currency,
                    salary = salary,
                    otherIncome = otherIncome,
                    externalTransfersIn = externalTransfersIn,
                    selfTransfersIn = selfTransfersIn,
                    accountRefunds = accountRefunds,
                ),
                outflow = AccountOutflow(
                    currency = currency,
                    externalTransfersOut = externalTransfersOut,
                    creditCardPayments = creditCardPayments,
                    cashWithdrawals = cashWithdrawals,
                    billPayments = billPayments,
                    posPurchases = posPurchases,
                    fees = fees,
                    loanRepayments = loanRepayments,
                    selfTransfersOut = selfTransfersOut,
                ),
            )

        fun zero(currency: Currency): CurrentAccountSummary =
            CurrentAccountSummary(
                inflow = AccountInflow.zero(currency),
                outflow = AccountOutflow.zero(currency),
            )

        fun aggregate(summaries: Collection<CurrentAccountSummary>): CurrentAccountSummary {
            if (summaries.isEmpty()) return zero(Currency.SAR)
            return CurrentAccountSummary(
                inflow = AccountInflow.sum(summaries.map { it.inflow }),
                outflow = AccountOutflow.sum(summaries.map { it.outflow }),
            )
        }
    }
}
