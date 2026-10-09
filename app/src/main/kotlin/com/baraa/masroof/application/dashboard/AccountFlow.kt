package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import java.math.RoundingMode

/**
 * Period cash flow for one owned current-account container.
 *
 * Buckets are explicit:
 * - **External** — money entering or leaving your accounts from outside the fleet.
 * - **Internal** — transfers between your own accounts (neutral at fleet level).
 * - **Loan** — reserved for loan drawdown/repayment (zero until wired).
 *
 * Totals are always derived; never stored separately.
 */
data class AccountFlow(
    val currency: Currency,
    val salary: Money,
    val otherIncome: Money,
    val externalTransfersIn: Money,
    val accountRefunds: Money,
    val internalTransfersIn: Money,
    val externalTransfersOut: Money,
    val creditCardPayments: Money,
    val cashWithdrawals: Money,
    val billPayments: Money,
    val posPurchases: Money,
    val fees: Money,
    val internalTransfersOut: Money,
    val loan: Money,
) {
    /** Salary, other income, external transfers in, and refunds credited to this account. */
    val externalIn: Money
        get() = salary + otherIncome + externalTransfersIn + accountRefunds

    /** Six standard outflow categories + loan. */
    val externalOut: Money
        get() = externalTransfersOut +
            creditCardPayments +
            cashWithdrawals +
            billPayments +
            posPurchases +
            fees +
            loan

    /** All money in, including transfers from other owned accounts. */
    val totalIn: Money
        get() = externalIn + internalTransfersIn

    /** All money out, including transfers to other owned accounts. */
    val totalOut: Money
        get() = externalOut + internalTransfersOut

    /**
     * External movement only — internal transfers excluded from in/out totals.
     * Matches v0.2.19 home and per-account detail behaviour.
     */
    fun externalSummary(): AccountFlowSummary =
        AccountFlowSummary(
            inflow = externalIn,
            outflow = externalOut,
            remaining = SignedMoneyAmount.difference(externalIn, externalOut),
            internalIn = internalTransfersIn,
            internalOut = internalTransfersOut,
            includesInternalInTotals = false,
        )

    /** Full account position: [totalIn] − [totalOut]. */
    fun accountSummary(): AccountFlowSummary =
        AccountFlowSummary(
            inflow = totalIn,
            outflow = totalOut,
            remaining = SignedMoneyAmount.difference(totalIn, totalOut),
            internalIn = internalTransfersIn,
            internalOut = internalTransfersOut,
            includesInternalInTotals = true,
        )

    init {
        listOf(
            salary,
            otherIncome,
            externalTransfersIn,
            accountRefunds,
            internalTransfersIn,
            externalTransfersOut,
            creditCardPayments,
            cashWithdrawals,
            billPayments,
            posPurchases,
            fees,
            internalTransfersOut,
            loan,
        ).forEach { require(it.currency == currency) }
    }

    companion object {
        fun zero(currency: Currency): AccountFlow =
            AccountFlow(
                currency = currency,
                salary = Money.zero(currency),
                otherIncome = Money.zero(currency),
                externalTransfersIn = Money.zero(currency),
                accountRefunds = Money.zero(currency),
                internalTransfersIn = Money.zero(currency),
                externalTransfersOut = Money.zero(currency),
                creditCardPayments = Money.zero(currency),
                cashWithdrawals = Money.zero(currency),
                billPayments = Money.zero(currency),
                posPurchases = Money.zero(currency),
                fees = Money.zero(currency),
                internalTransfersOut = Money.zero(currency),
                loan = Money.zero(currency),
            )

        fun from(summary: CurrentAccountSummary): AccountFlow =
            AccountFlow(
                currency = summary.currency,
                salary = summary.inflow.salary,
                otherIncome = summary.inflow.otherIncome,
                externalTransfersIn = summary.inflow.externalTransfersIn,
                accountRefunds = summary.inflow.accountRefunds,
                internalTransfersIn = summary.inflow.selfTransfersIn,
                externalTransfersOut = summary.outflow.externalTransfersOut,
                creditCardPayments = summary.outflow.creditCardPayments,
                cashWithdrawals = summary.outflow.cashWithdrawals,
                billPayments = summary.outflow.billPayments,
                posPurchases = summary.outflow.posPurchases,
                fees = summary.outflow.fees,
                internalTransfersOut = summary.outflow.selfTransfersOut,
                loan = summary.outflow.loanRepayments,
            )

        fun sum(flows: Collection<AccountFlow>): AccountFlow {
            if (flows.isEmpty()) return zero(Currency.SAR)
            return flows.reduce { acc, next -> acc + next }
        }
    }

    operator fun plus(other: AccountFlow): AccountFlow {
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return AccountFlow(
            currency = currency,
            salary = salary + other.salary,
            otherIncome = otherIncome + other.otherIncome,
            externalTransfersIn = externalTransfersIn + other.externalTransfersIn,
            accountRefunds = accountRefunds + other.accountRefunds,
            internalTransfersIn = internalTransfersIn + other.internalTransfersIn,
            externalTransfersOut = externalTransfersOut + other.externalTransfersOut,
            creditCardPayments = creditCardPayments + other.creditCardPayments,
            cashWithdrawals = cashWithdrawals + other.cashWithdrawals,
            billPayments = billPayments + other.billPayments,
            posPurchases = posPurchases + other.posPurchases,
            fees = fees + other.fees,
            internalTransfersOut = internalTransfersOut + other.internalTransfersOut,
            loan = loan + other.loan,
        )
    }
}

/** Computed in/out/remaining view derived from [AccountFlow] buckets. */
data class AccountFlowSummary(
    val inflow: Money,
    val outflow: Money,
    val remaining: SignedMoneyAmount,
    val internalIn: Money,
    val internalOut: Money,
    val includesInternalInTotals: Boolean,
) {
    val includesSelfTransfersInTotals: Boolean get() = includesInternalInTotals

    val selfTransfersIn: Money get() = internalIn

    val selfTransfersOut: Money get() = internalOut

    fun toTotals(): AccountFlowTotals = this
}

/**
 * Fleet view: sums each account's [AccountFlow] buckets, then exposes both summaries.
 *
 * [accountSummary] — all in minus all out across every owned account.
 * [externalSummary] — internal transfers excluded (neutral at fleet level).
 */
data class FleetAccountFlow(
    val accounts: List<AccountFlow>,
) {
    val currency: Currency?
        get() = accounts.firstOrNull()?.currency

    val combined: AccountFlow?
        get() {
            if (accounts.isEmpty()) return null
            return AccountFlow.sum(accounts)
        }

    fun accountSummary(): AccountFlowSummary? = combined?.accountSummary()

    fun externalSummary(): AccountFlowSummary? = combined?.externalSummary()
}

fun CurrentAccountSummary.accountFlow(): AccountFlow = AccountFlow.from(this)

fun SignedMoneyAmount.plus(other: SignedMoneyAmount): SignedMoneyAmount {
    require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }
    return SignedMoneyAmount(
        amount.add(other.amount).setScale(Money.SCALE, RoundingMode.HALF_EVEN),
        currency,
    )
}
