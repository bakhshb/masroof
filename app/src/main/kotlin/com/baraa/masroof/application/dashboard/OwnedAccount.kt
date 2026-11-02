package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank

/**
 * Period cash-flow view for one owned current account (e.g. last4 `3001`).
 *
 * Buckets live on [flow]; [remaining] and [cashPosition] are always derived.
 * Credit-card **purchases** (card container) are excluded — only [creditCardPayment]
 * (سداد بطاقة from this account) is tracked here.
 */
data class OwnedAccount(
    val id: String,
    val bank: Bank,
    val maskedNumber: String,
    val containerId: String,
    val summary: CurrentAccountSummary,
) {
    val flow: AccountFlow get() = summary.accountFlow()

    val currency: Currency get() = flow.currency

    // —— Inflows ——
    val salary: Money get() = flow.salary
    val otherIncome: Money get() = flow.otherIncome
    val externalTransfersIn: Money get() = flow.externalTransfersIn
    val internalTransfersIn: Money get() = flow.internalTransfersIn
    val externalIn: Money get() = flow.externalIn
    val totalIn: Money get() = flow.totalIn

    // —— Outflows ——
    val externalTransfersOut: Money get() = flow.externalTransfersOut
    /** سداد بطاقة ائتمانية — settlement from this account to a credit card. */
    val creditCardPayment: Money get() = flow.creditCardPayments
    val cashWithdrawal: Money get() = flow.cashWithdrawals
    val billPayment: Money get() = flow.billPayments
    /** POS / مدى debited from this account. */
    val posPurchase: Money get() = flow.posPurchases
    val fees: Money get() = flow.fees
    val loan: Money get() = flow.loan
    val internalTransfersOut: Money get() = flow.internalTransfersOut
    val externalOut: Money get() = flow.externalOut
    val totalOut: Money get() = flow.totalOut

    /** وارد − منصرف خارجي (self-transfers excluded). */
    val remaining: SignedMoneyAmount get() = flow.externalSummary().remaining

    /** كل وارد − كل منصرف (includes internal transfers). */
    val cashPosition: SignedMoneyAmount get() = flow.accountSummary().remaining

    fun externalSummary(): AccountFlowSummary = flow.externalSummary()

    fun accountSummary(): AccountFlowSummary = flow.accountSummary()

    companion object {
        fun from(
            bank: Bank,
            maskedNumber: String,
            summary: CurrentAccountSummary,
        ): OwnedAccount =
            OwnedAccount(
                id = RegistryDisplayLabels.accountLast4(maskedNumber),
                bank = bank,
                maskedNumber = maskedNumber,
                containerId = FinancialContainerIdFactory.accountId(bank, maskedNumber),
                summary = summary,
            )
    }
}
