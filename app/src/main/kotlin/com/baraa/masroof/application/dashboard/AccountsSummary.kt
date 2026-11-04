package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank

/**
 * Fleet view across [OwnedAccount] instances for the salary period.
 *
 * [totalInflow] and [totalOutflow] are external movement only. A transfer
 * between owned accounts is not income and not spending, even when the
 * destination account is at the same bank.
 * [totalRemaining] is all in − all out per account, summed. Those internal
 * legs cancel across the fleet, so remaining still matches external remaining.
 * Per-account cards use [OwnedAccount.remaining] (external movement only).
 */
data class AccountsSummary(
    val accounts: List<OwnedAccount>,
) {
    private val fleet: FleetAccountFlow
        get() = FleetAccountFlow(accounts.map { it.flow })

    val currency: Currency?
        get() = fleet.currency

    /** Sum of every account's total in − total out. */
    val totalRemaining: SignedMoneyAmount?
        get() = fleet.accountSummary()?.remaining

    /** Salary, other income, external transfers in, and account refunds. Excludes transfers between owned accounts. */
    val totalInflow: Money?
        get() = fleet.externalSummary()?.inflow

    /** Money that left the owned fleet. Excludes transfers between owned accounts. */
    val totalOutflow: Money?
        get() = fleet.externalSummary()?.outflow

    fun accountSummary(): AccountFlowSummary? = fleet.accountSummary()

    fun externalSummary(): AccountFlowSummary? = fleet.externalSummary()

    companion object {
        fun fromSummaries(
            accounts: List<Pair<Bank, String>>,
            summaries: List<CurrentAccountSummary>,
        ): AccountsSummary {
            require(accounts.size == summaries.size)
            return AccountsSummary(
                accounts = accounts.zip(summaries) { (bank, masked), summary ->
                    OwnedAccount.from(bank, masked, summary)
                },
            )
        }
    }
}
