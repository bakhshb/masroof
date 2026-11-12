package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.dashboard.CommitmentsOverview
import com.baraa.masroof.application.dashboard.DashboardOverview
import com.baraa.masroof.application.dashboard.DashboardSalaryPeriod
import com.baraa.masroof.application.dashboard.salaryPeriod

/** The state while [period] loads. A refresh of the period already shown keeps its content on screen. */
internal fun DashboardUiState.loadingPeriod(
    period: DashboardSalaryPeriod,
    periodLabel: String,
    periodAdjustmentHint: String?,
    samePeriodRefresh: Boolean,
): DashboardUiState =
    if (samePeriodRefresh) {
        copy(
            loading = true,
            error = null,
            period = period,
            periodLabel = periodLabel,
            periodAdjustmentHint = periodAdjustmentHint,
        )
    } else {
        copy(
            loading = true,
            error = null,
            period = period,
            periodLabel = periodLabel,
            periodAdjustmentHint = periodAdjustmentHint,
            summary = null,
            currentAccount = null,
            creditFacilities = null,
            loansOverview = null,
            commitmentsOverview = CommitmentsOverview.empty(),
            merchantSpending = com.baraa.masroof.application.dashboard.MerchantSpendingOverview.empty(),
            dailySpendingTrend = null,
            spendingTrends = null,
            accountsFleet = null,
            recentTransactions = emptyList(),
            allTransactions = emptyList(),
            flowDetailGrouping = null,
            transactionAccountInvolvement = emptyMap(),
            transactionCardInvolvement = emptyMap(),
            transactionLoanInvolvement = emptyMap(),
            transactionDebitSpendInvolvement = emptyMap(),
        )
    }

/** The state once [overview] is loaded. The caller projects the transaction list afterwards. */
internal fun DashboardUiState.withOverview(
    overview: DashboardOverview,
    previews: List<TransactionPreviewUi>,
    loadedLabel: String,
    loadedHint: String?,
    unknownCards: List<UnknownCardCandidateUi>,
    ownedCards: List<OwnedCardUi>,
    ownedAccounts: List<OwnedAccountUi>,
    committedIds: Set<String>,
): DashboardUiState =
    copy(
        loading = false,
        period = overview.salaryPeriod,
        periodLabel = loadedLabel,
        periodAdjustmentHint = loadedHint,
        summary = overview.summary,
        currentAccount = overview.currentAccount,
        creditFacilities = overview.creditFacilities,
        loansOverview = overview.loansOverview,
        commitmentsOverview = overview.commitmentsOverview,
        merchantSpending = overview.merchantSpending,
        dailySpendingTrend = overview.dailySpendingTrend,
        spendingTrends = overview.spendingTrends,
        bankHierarchy = overview.bankHierarchy,
        accountsFleet = overview.accountsFleet,
        recentTransactions = previews.take(DashboardViewModel.RECENT_TRANSACTION_LIMIT),
        allTransactions = previews,
        flowDetailGrouping = overview.flowDetailGrouping,
        transactionAccountInvolvement = overview.transactionAccountInvolvement,
        transactionCardInvolvement = overview.transactionCardInvolvement,
        transactionLoanInvolvement = overview.transactionLoanInvolvement,
        transactionDebitSpendInvolvement = overview.transactionDebitSpendInvolvement,
        isCurrentPeriod = overview.isCurrentPeriod,
        error = null,
        unknownCards = unknownCards,
        ownedCards = ownedCards,
        ownedAccounts = ownedAccounts,
        ownedAccountContainerIds = overview.ownedAccountContainerIds,
        committedSourceTransactionIds = committedIds,
    )
