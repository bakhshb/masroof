package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.presentation.navigation.AnalyticsNavigation

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.baraa.masroof.ui.components.coachMarkTarget
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CreditCardDashboardRow
import com.baraa.masroof.application.dashboard.DebitCardOverview
import com.baraa.masroof.application.dashboard.LoanOverview
import com.baraa.masroof.application.dashboard.CurrentAccountFlowDetailGrouping
import com.baraa.masroof.application.dashboard.DashboardLayoutSnapshot
import com.baraa.masroof.application.dashboard.DashboardSectionId
import com.baraa.masroof.application.dashboard.MonthlyFinancialSummary
import com.baraa.masroof.presentation.common.IconTextButton
import com.baraa.masroof.presentation.common.IconTextButtonOutlined
import com.baraa.masroof.presentation.common.LongPullToRefreshBox
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofPeriodPill
import com.baraa.masroof.presentation.common.MasroofSectionHeader
import com.baraa.masroof.presentation.common.SmsPermissionNotice
import com.baraa.masroof.presentation.common.UnregisteredCardsNotice
import com.baraa.masroof.presentation.common.ForeignCurrencyNotice
import com.baraa.masroof.ui.theme.MasroofSpacing

@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel,
    onOpenAllTransactions: () -> Unit = {},
    onOpenTransaction: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenCommitments: () -> Unit = onOpenSettings,
    onManageCardsInSettings: () -> Unit = onOpenSettings,
    onOpenAccountsSummary: () -> Unit = onOpenSettings,
    onOpenCardsSummary: () -> Unit = onOpenSettings,
    onOpenLoansSummary: () -> Unit = onOpenSettings,
    onOpenMerchantsSummary: () -> Unit = {},
    onOpenLoanDetail: (LoanOverview) -> Unit = { onOpenLoansSummary() },
    onOpenCardDetail: (CreditCardDashboardRow) -> Unit = { onOpenCardsSummary() },
    onOpenDebitDetail: (DebitCardOverview) -> Unit = { onOpenCardsSummary() },
    onRequestSmsPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onOpenReview: () -> Unit = {},
    onImportSms: () -> Unit = onOpenSettings,
) {
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    val state by viewModel.uiState.collectAsState()
    DashboardScreen(
        state = state,
        onPrevious = viewModel::goToPreviousPeriod,
        onNext = viewModel::goToNextPeriod,
        onCurrent = viewModel::goToCurrentPeriod,
        onRetry = viewModel::refresh,
        onRescan = onImportSms,
        onOpenAllTransactions = onOpenAllTransactions,
        onOpenTransaction = onOpenTransaction,
        onOpenCommitments = onOpenCommitments,
        onManageCardsInSettings = onManageCardsInSettings,
        onOpenAccountsSummary = onOpenAccountsSummary,
        onOpenCardsSummary = onOpenCardsSummary,
        onOpenLoansSummary = onOpenLoansSummary,
        onOpenMerchantsSummary = onOpenMerchantsSummary,
        onOpenLoanDetail = onOpenLoanDetail,
        onOpenCardDetail = onOpenCardDetail,
        onOpenDebitDetail = onOpenDebitDetail,
        onRequestSmsPermission = onRequestSmsPermission,
        onOpenAppSettings = onOpenAppSettings,
        onOpenReview = onOpenReview,
        onOpenCustomize = viewModel::openCustomizeSheet,
        onDismissCustomize = viewModel::dismissCustomizeSheet,
        onSaveCustomize = viewModel::saveCustomizeLayout,
        onToggleCustomizeSection = viewModel::toggleCustomizeSection,
        onMoveCustomizeSection = viewModel::moveCustomizeSection,
        onToggleCustomizeQuickExpense = viewModel::toggleCustomizeQuickExpense,
        onToggleCustomizeQuickIncome = viewModel::toggleCustomizeQuickIncome,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardScreen(
    state: DashboardUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCurrent: () -> Unit,
    onRetry: () -> Unit,
    onRescan: () -> Unit,
    onOpenAllTransactions: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    onOpenCommitments: () -> Unit,
    onManageCardsInSettings: () -> Unit,
    onOpenAccountsSummary: () -> Unit,
    onOpenCardsSummary: () -> Unit,
    onOpenLoansSummary: () -> Unit,
    onOpenMerchantsSummary: () -> Unit,
    onOpenLoanDetail: (LoanOverview) -> Unit,
    onOpenCardDetail: (CreditCardDashboardRow) -> Unit,
    onOpenDebitDetail: (DebitCardOverview) -> Unit,
    onRequestSmsPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenCustomize: () -> Unit,
    onDismissCustomize: () -> Unit,
    onSaveCustomize: () -> Unit,
    onToggleCustomizeSection: (DashboardSectionId) -> Unit,
    onMoveCustomizeSection: (DashboardSectionId, Int) -> Unit,
    onToggleCustomizeQuickExpense: () -> Unit,
    onToggleCustomizeQuickIncome: () -> Unit,
    onOpenReview: () -> Unit = {},
) {
    var labAnalysisOpen by rememberSaveable { mutableStateOf(false) }
    val labDestination by AnalyticsNavigation.target.collectAsState()
    LaunchedEffect(labDestination) { if (labDestination != null) labAnalysisOpen = true }
    if (labAnalysisOpen) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            AnalyticsNavigation.setAnalysisVisible(true)
            onDispose { AnalyticsNavigation.setAnalysisVisible(false) }
        }
        com.baraa.masroof.presentation.analytics.AnalyticsRoute(
            onBack = { labAnalysisOpen = false; AnalyticsNavigation.finish() },
            onOpenTransaction = onOpenTransaction,
            onOpenReview = onOpenReview, onRequestSmsPermission = onRequestSmsPermission, onRescan = onRescan,
        )
        return
    }
    var flowDetailMode by rememberSaveable { mutableStateOf<DashboardFlowDetailMode?>(null) }
    val context = LocalContext.current
    val periodRangeLabel = state.period?.let {
        FinancialPeriodUiFormatter.formatRange(context, it, compact = true)
    } ?: state.periodLabel
    val isPullRefreshing = state.loading && state.summary != null
    val activeLayout = state.customizeDraft ?: state.dashboardLayout

    if (flowDetailMode != null && state.currentAccount != null) {
        BackHandler { flowDetailMode = null }
        DashboardFlowDetailScreen(
            mode = flowDetailMode!!,
            summary = state.currentAccount,
            state = state,
            transactions = state.allTransactions,
            grouping = state.flowDetailGrouping ?: CurrentAccountFlowDetailGrouping.empty(),
            onBack = { flowDetailMode = null },
            onOpenTransaction = onOpenTransaction,
        )
        return
    }

    if (state.customizeSheetOpen && state.customizeDraft != null) {
        DashboardCustomizeBottomSheet(
            draft = state.customizeDraft,
            onDismiss = onDismissCustomize,
            onSave = onSaveCustomize,
            onToggleSection = onToggleCustomizeSection,
            onMoveSection = onMoveCustomizeSection,
            onToggleQuickExpense = onToggleCustomizeQuickExpense,
            onToggleQuickIncome = onToggleCustomizeQuickIncome,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LongPullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = onRetry,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MasroofSpacing.screenHorizontal)
                    .padding(top = MasroofSpacing.screenVertical)
                    .padding(bottom = MasroofSpacing.screenVertical),
                verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
            ) {
                MasroofPeriodPill(
                    label = periodRangeLabel,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    previousContentDescription = stringResource(R.string.dashboard_period_previous),
                    nextContentDescription = stringResource(R.string.dashboard_period_next),
                    onCustomize = onOpenCustomize,
                    customizeLabel = stringResource(R.string.dashboard_customize),
                    guidance = state.periodAdjustmentHint,
                    modifier = Modifier.coachMarkTarget(com.baraa.masroof.presentation.navigation.HomeTour.PERIOD),
                )

                if (!state.isCurrentPeriod) {
                    IconTextButtonOutlined(
                        onClick = onCurrent,
                        icon = MasroofIcons.backToCurrent,
                        text = stringResource(R.string.dashboard_back_to_current),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (!state.smsPermissionGranted) {
                    SmsPermissionNotice(
                        onRequestPermission = onRequestSmsPermission,
                        onOpenAppSettings = onOpenAppSettings,
                    )
                }

                when {
                    state.loading && state.summary == null -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(MasroofSpacing.contentPaddingLarge),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    state.error != null && state.summary == null -> {
                        DashboardErrorContent(onRetry = onRetry)
                    }

                    else -> {
                        val summary = state.summary
                        val currentAccount = state.currentAccount
                        if (summary != null && currentAccount != null) {
                            DashboardCustomizableSections(
                                state = state,
                                summary = summary,
                                layout = activeLayout,
                                editing = state.customizeSheetOpen,
                                onOpenExpenseDetails = { flowDetailMode = DashboardFlowDetailMode.Expense },
                                onOpenIncomeDetails = { flowDetailMode = DashboardFlowDetailMode.Income },
                                onManageCardsInSettings = onManageCardsInSettings,
                                onOpenCommitments = onOpenCommitments,
                                onOpenAccountsSummary = onOpenAccountsSummary,
                                onOpenCardsSummary = onOpenCardsSummary,
                                onOpenLoansSummary = onOpenLoansSummary,
                                onOpenMerchantsSummary = onOpenMerchantsSummary,
                                onOpenAllTransactions = onOpenAllTransactions,
                                onOpenTransaction = onOpenTransaction,
                                onRescan = onRescan,
                                onOpenAnalytics = { labAnalysisOpen = true },
                            )

                            if (summary.excludedOtherCurrencyCount > 0) {
                                ForeignCurrencyNotice(excludedCount = summary.excludedOtherCurrencyCount)
                            }

                            if (state.error != null) {
                                DashboardErrorContent(onRetry = onRetry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardCustomizableSections(
    state: DashboardUiState,
    summary: MonthlyFinancialSummary,
    layout: DashboardLayoutSnapshot,
    editing: Boolean,
    onOpenExpenseDetails: () -> Unit,
    onOpenIncomeDetails: () -> Unit,
    onManageCardsInSettings: () -> Unit,
    onOpenCommitments: () -> Unit,
    onOpenAccountsSummary: () -> Unit,
    onOpenCardsSummary: () -> Unit,
    onOpenLoansSummary: () -> Unit,
    onOpenMerchantsSummary: () -> Unit,
    onOpenAllTransactions: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    onRescan: () -> Unit,
    onOpenAnalytics: () -> Unit,
) {
    state.currentAccount ?: return
    val accountsFleet = resolveDashboardAccountsFleet(
        ownedAccounts = state.ownedAccounts,
        fleet = state.accountsFleet,
    )

    layout.orderedVisibleSections().filter { it.id != DashboardSectionId.CARDS && it.id != DashboardSectionId.LOANS }.forEach { entry ->
        Column(
            modifier = Modifier.dashboardSectionFrame(entry.size, editing),
        ) {
            when (entry.id) {
                DashboardSectionId.HERO -> {
                    DashboardHeroCard(
                        accountsFleet = accountsFleet,
                        onIncome = onOpenIncomeDetails,
                        onOutflow = onOpenExpenseDetails,
                        modifier = Modifier.coachMarkTarget(com.baraa.masroof.presentation.navigation.HomeTour.SUMMARY),
                    )
                }

                DashboardSectionId.QUICK -> {
                    DashboardQuickSummaryRow(
                        trend = state.spendingTrends?.overall,
                        showExpense = layout.quickExpenseVisible,
                        showIncome = layout.quickIncomeVisible,
                    )
                    if (state.isCurrentPeriod) {
                        androidx.compose.foundation.layout.Spacer(Modifier.height(MasroofSpacing.sectionGap))
                        com.baraa.masroof.presentation.analytics.ForecastCard(onOpenAnalytics)
                    }
                }

                DashboardSectionId.ACCOUNTS -> DashboardShortcuts(onOpenAccountsSummary, onOpenCardsSummary, onOpenLoansSummary, onOpenCommitments)
                // Entity cards now live in their unified summary screens.
                DashboardSectionId.CARDS, DashboardSectionId.LOANS -> Unit

                DashboardSectionId.COMMITMENTS -> {
                    CommitmentsSection(overview = state.commitmentsOverview, onOpenCredit = onOpenCardsSummary)
                }

                DashboardSectionId.MERCHANTS -> {
                    state.spendingTrends?.let { trends ->
                        HomeSpendingTrendSection(trends)

                    }
                }

                DashboardSectionId.TRANSACTIONS -> {
                    MasroofSectionHeader(
                        title = stringResource(R.string.dashboard_recent_title),
                        icon = MasroofIcons.recentTransactions,
                        onViewAll = onOpenAllTransactions,
                        viewAllLabel = stringResource(R.string.dashboard_view_all),
                    )

                    state.unknownCards.firstOrNull()?.let { firstUnknown ->
                        UnregisteredCardsNotice(
                            firstLast4 = firstUnknown.last4,
                            extraCount = (state.unknownCards.size - 1).coerceAtLeast(0),
                            onManageCards = onManageCardsInSettings,
                        )
                    }

                    if (summary.transactionCount == 0) {
                        Text(
                            stringResource(R.string.dashboard_empty_period),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(MasroofSpacing.cardInnerGap))
                        IconTextButton(
                            onClick = onRescan,
                            icon = MasroofIcons.rescan,
                            text = stringResource(R.string.dashboard_rescan_sms),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        com.baraa.masroof.presentation.common.MasroofCard {
                            state.recentTransactions.forEach { row ->
                                TransactionPreviewRow(row = row, ownedCards = state.ownedCards,
                                    onClick = { onOpenTransaction(row.id) })
                            }
                        }

                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardErrorContent(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = MasroofIcons.error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.size(MasroofSpacing.cardInnerGap))
        Text(stringResource(R.string.dashboard_load_error))
    }
    IconTextButton(
        onClick = onRetry,
        icon = MasroofIcons.retry,
        text = stringResource(R.string.dashboard_retry),
    )
}
