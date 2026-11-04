package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.dashboard.AccountsSummary
import com.baraa.masroof.application.dashboard.CurrentAccountSummary
import com.baraa.masroof.application.dashboard.LoanOverview
import com.baraa.masroof.application.dashboard.LoansOverview
import com.baraa.masroof.application.dashboard.OwnedAccount
import com.baraa.masroof.application.dashboard.SignedMoneyAmount
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.presentation.common.MasroofCardAccent
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardSummaryHeroSpecsTest {
    @Test
    fun buildAccountsSummaryHeroSpec_usesSpotlightAndSecondaryGrid() {
        val fleet = AccountsSummary(
            accounts = listOf(
                ownedAccount(salary = "5000", externalOut = "3500"),
            ),
        )

        val spec = buildAccountsSummaryHeroSpec(
            fleet = fleet,
            remainingTitle = "Total remaining",
            inflowTitle = "Total inflow",
            outflowTitle = "Total spent",
            unavailableLabel = "Unavailable",
            formulaHint = "formula",
            footerHint = "fleet hint",
            languageTag = "en",
        )

        assertEquals(MasroofCardAccent.Account, spec.accent)
        assertEquals("Total remaining", spec.primary.title)
        assertEquals(DashboardMetricTone.Signed, spec.primary.tone)
        assertEquals("formula", spec.primary.hint)
        assertEquals(2, spec.secondary.size)
        assertEquals("Total inflow", spec.secondary[0].title)
        assertEquals(DashboardMetricTone.Inflow, spec.secondary[0].tone)
        assertEquals("Total spent", spec.secondary[1].title)
        assertEquals(DashboardMetricTone.Outflow, spec.secondary[1].tone)
        assertEquals("fleet hint", spec.footerHint)
    }

    @Test
    fun buildLoansSummaryHeroSpec_usesLiabilityAccent() {
        val overview = LoansOverview(
            loans = listOf(
                LoanOverview(
                    bank = Bank.BANK_ALJAZIRA,
                    loanType = LoanType.PERSONAL,
                    displayLabel = "Personal loan",
                    remainingBalance = Money.of("100000.00", Currency.SAR),
                    remainingBalanceAsOf = null,
                    salaryPeriodPayment = SignedMoneyAmount.of(Money.of("1500.00", Currency.SAR)),
                    salaryPeriodLabel = "27 August",
                ),
            ),
            salaryPeriodLabel = "27 August",
            currency = Currency.SAR,
        )

        val spec = buildLoansSummaryHeroSpec(
            overview = overview,
            remainingTitle = "Total remaining",
            unavailableLabel = "Unavailable",
            languageTag = "en",
        )

        assertEquals(MasroofCardAccent.Liability, spec.accent)
        assertEquals("Total remaining", spec.primary.title)
        assertEquals(DashboardMetricTone.Liability, spec.primary.tone)
        assertEquals(0, spec.secondary.size)
    }

    private fun ownedAccount(
        salary: String,
        externalOut: String,
    ): OwnedAccount {
        val summary = CurrentAccountSummary.of(
            currency = Currency.SAR,
            salary = Money.of(salary, Currency.SAR),
            otherIncome = Money.zero(Currency.SAR),
            externalTransfersIn = Money.zero(Currency.SAR),
            selfTransfersIn = Money.zero(Currency.SAR),
            selfTransfersOut = Money.zero(Currency.SAR),
            creditCardPayments = Money.zero(Currency.SAR),
            billPayments = Money.zero(Currency.SAR),
            externalTransfersOut = Money.of(externalOut, Currency.SAR),
            cashWithdrawals = Money.zero(Currency.SAR),
            posPurchases = Money.zero(Currency.SAR),
            fees = Money.zero(Currency.SAR),
            loanRepayments = Money.zero(Currency.SAR),
        )
        return OwnedAccount(
            id = "3001",
            bank = Bank.BANK_ALJAZIRA,
            maskedNumber = "3001",
            containerId = "account:bank_aljazira:3001",
            summary = summary,
        )
    }
}
