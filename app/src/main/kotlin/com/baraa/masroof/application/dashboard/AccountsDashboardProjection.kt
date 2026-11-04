package com.baraa.masroof.application.dashboard

data class AccountsDashboardSection(
    val fleet: CurrentAccountSummary,
    val perAccount: List<OwnedAccountPeriodSummary>,
    val accountsFleet: AccountsSummary,
    val flowDetail: CurrentAccountFlowDetailGrouping,
    val transactionAccountInvolvement: Map<String, Set<String>>,
)

/** Owned current-account summaries, flows, and account involvement for the displayed period. */
object AccountsDashboardProjection {
    fun build(context: DashboardProjectionContext): AccountsDashboardSection =
        with(context) {
            val perAccount = OwnedAccountPeriodSummaryCalculator.summarize(
                ownedAccounts = ownedAccounts,
                transactions = transactions,
                parsedRecords = parsedRecords,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents,
                debitCardScope = debitCardScope,
            )
            val flowDetail = CurrentAccountFlowDetailGrouper.group(
                transactions = transactions,
                parsedRecords = parsedRecords,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents,
                ownedAccountContainerIds = ownedAccountContainerIds,
                debitCardScope = debitCardScope,
            )
            val fleet = CurrentAccountSummaryCalculator.summarize(flowDetail, primaryCurrency, sarEquivalents)
            AccountsDashboardSection(
                fleet = fleet,
                perAccount = perAccount,
                accountsFleet = AccountsSummary.fromSummaries(
                    accounts = ownedAccounts.map { it.bank to it.maskedNumber },
                    summaries = perAccount.map { it.summary },
                ),
                flowDetail = flowDetail,
                transactionAccountInvolvement = AccountTransactionInvolvementResolver.buildIndex(
                    transactions = transactions,
                    parsedRecords = parsedRecords,
                    ownedAccounts = ownedAccounts,
                    debitCardScope = debitCardScope,
                ),
            )
        }
}
