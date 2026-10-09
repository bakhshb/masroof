package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import java.math.RoundingMode

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

    val netMovement: SignedMoneyAmount
        get() {
            val net = inflow.coreTotal.amount
                .subtract(outflow.coreTotal.amount)
                .setScale(Money.SCALE, RoundingMode.HALF_EVEN)
            return SignedMoneyAmount(net, currency)
        }

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

        fun aggregate(summaries: Collection<CurrentAccountSummary>): CurrentAccountSummary =
            AccountFlowAggregator.aggregate(summaries)
    }
}

/**
 * Period spending: everything that left the current account in the period.
 */
data class SpendingSplitSummary(
    val currency: Currency,
    val totalSpending: Money,
    val creditCardPurchases: SignedMoneyAmount,
) {
    init {
        require(totalSpending.currency == currency)
        require(creditCardPurchases.currency == currency)
    }
}
