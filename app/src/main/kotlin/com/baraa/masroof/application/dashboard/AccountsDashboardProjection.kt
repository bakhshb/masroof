package com.baraa.masroof.application.dashboard

data class AccountsDashboardSection(
    val fleet: CurrentAccountSummary,
    val spendingSplit: SpendingSplitSummary,
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
                rawSmsById = rawSmsById,
                debitCardScope = debitCardScope,
            )
            AccountsDashboardSection(
                fleet = CurrentAccountSummaryCalculator.summarize(
                    transactions = transactions,
                    parsedRecords = parsedRecords,
                    primaryCurrency = primaryCurrency,
                    sarEquivalents = sarEquivalents,
                    ownedAccountContainerIds = ownedAccountContainerIds,
                    ownedAccountLast4s = ownedAccountLast4s,
                    rawSmsById = rawSmsById,
                    debitCardScope = debitCardScope,
                ),
                spendingSplit = CurrentAccountSummaryCalculator.spendingSplit(
                    transactions = transactions,
                    parsedRecords = parsedRecords,
                    primaryCurrency = primaryCurrency,
                    sarEquivalents = sarEquivalents,
                    ownedAccountContainerIds = ownedAccountContainerIds,
                    ownedAccountLast4s = ownedAccountLast4s,
                    rawSmsById = rawSmsById,
                    debitCardScope = debitCardScope,
                ),
                perAccount = perAccount,
                accountsFleet = AccountsSummary.fromSummaries(
                    accounts = ownedAccounts.map { it.bank to it.maskedNumber },
                    summaries = perAccount.map { it.summary },
                ),
                flowDetail = CurrentAccountFlowDetailGrouper.group(
                    transactions = transactions,
                    parsedRecords = parsedRecords,
                    primaryCurrency = primaryCurrency,
                    sarEquivalents = sarEquivalents,
                    ownedAccountContainerIds = ownedAccountContainerIds,
                    ownedAccountLast4s = ownedAccountLast4s,
                    rawSmsById = rawSmsById,
                    debitCardScope = debitCardScope,
                ),
                transactionAccountInvolvement = AccountTransactionInvolvementResolver.buildIndex(
                    transactions = transactions,
                    parsedRecords = parsedRecords,
                    rawSmsById = rawSmsById,
                    ownedAccounts = ownedAccounts,
                ),
            )
        }
}
