package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.period.FinancialPeriod

data class DashboardOverview(
    val period: FinancialPeriod,
    val summary: MonthlyFinancialSummary,
    val currentAccount: CurrentAccountSummary,
    /** All transactions in the selected period, newest first. */
    val transactions: List<com.baraa.masroof.domain.model.FinancialTransaction>,
    val creditFacilities: CreditFacilitiesOverview,
    val loansOverview: LoansOverview? = null,
    val commitmentsOverview: CommitmentsOverview = CommitmentsOverview.empty(),
    val merchantSpending: MerchantSpendingOverview = MerchantSpendingOverview.empty(),
    val dailySpendingTrend: DailySpendingTrend = DailySpendingTrend.empty(period),
    val spendingTrends: DashboardSpendingTrends? = null,
    val bankHierarchy: BankHierarchyOverview? = null,
    val accountsFleet: AccountsSummary? = null,
    val ownedAccountPeriodSummaries: List<OwnedAccountPeriodSummary>,
    val flowDetailGrouping: CurrentAccountFlowDetailGrouping,
    /** Transaction id → owned account container ids (SMS-resolved, SingleAccount scope). */
    val transactionAccountInvolvement: Map<String, Set<String>> = emptyMap(),
    /** Transaction id → card keys (`bankId:last4`) from parsed SMS card refs. */
    val transactionCardInvolvement: Map<String, Set<String>> = emptyMap(),
    /** Transaction id → loan container ids for SMS-attributed loan repayments. */
    val transactionLoanInvolvement: Map<String, Set<String>> = emptyMap(),
    val transactionDebitSpendInvolvement: Map<String, Set<String>> = emptyMap(),
    /** Transaction id → display facts (card last4, effective type, SAR equivalent). */
    val transactionFacts: Map<String, DashboardTransactionFacts> = DashboardTransactionFactsBuilder.build(
        transactions = transactions,
        cardInvolvement = transactionCardInvolvement,
        loanInvolvement = transactionLoanInvolvement,
    ),
    /** Container ids of owned registry accounts, for account filtering. */
    val ownedAccountContainerIds: Set<String> = emptySet(),
    val isCurrentPeriod: Boolean,
    val labSarEquivalents: Map<String, com.baraa.masroof.core.money.Money> = emptyMap(),
)
