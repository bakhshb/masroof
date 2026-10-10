package com.baraa.masroof.presentation.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.baraa.masroof.application.dashboard.CreditCardDashboardRow
import com.baraa.masroof.application.dashboard.DebitCardOverview
import com.baraa.masroof.application.notification.NotificationAction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.presentation.common.MasroofScreenBackground
import com.baraa.masroof.presentation.dashboard.AccountsSummaryRoute
import com.baraa.masroof.presentation.dashboard.CardOwnershipKey
import com.baraa.masroof.presentation.dashboard.CardsSummaryRoute
import com.baraa.masroof.presentation.dashboard.DashboardRoute
import com.baraa.masroof.presentation.dashboard.DashboardUiState
import com.baraa.masroof.presentation.dashboard.DashboardViewModel
import com.baraa.masroof.presentation.dashboard.LoanOwnershipKey
import com.baraa.masroof.presentation.dashboard.LoansSummaryRoute
import com.baraa.masroof.presentation.dashboard.MerchantSpendingSummaryRoute
import com.baraa.masroof.presentation.dashboard.TransactionDetailScreen
import com.baraa.masroof.presentation.dashboard.TransactionListFilterState
import com.baraa.masroof.presentation.dashboard.TransactionListScreen
import com.baraa.masroof.presentation.notification.NotificationCenterRoute
import com.baraa.masroof.presentation.notification.NotificationCenterViewModel
import com.baraa.masroof.presentation.notification.notificationCenterExternalState
import com.baraa.masroof.presentation.onboarding.OnboardingRoute
import com.baraa.masroof.presentation.onboarding.OnboardingStep
import com.baraa.masroof.presentation.onboarding.OnboardingViewModel
import com.baraa.masroof.presentation.review.ReviewRoute
import com.baraa.masroof.presentation.review.ReviewViewModel
import com.baraa.masroof.presentation.settings.SettingsRoute
import com.baraa.masroof.presentation.settings.SettingsViewModel
import com.baraa.masroof.presentation.statement.StatementReconciliationViewModel

/**
 * Minimal root composition: onboarding while incomplete, dashboard + review after HOME.
 */
@Composable
fun MasroofRoot(
    onboardingViewModel: OnboardingViewModel,
    dashboardViewModel: DashboardViewModel,
    reviewViewModel: ReviewViewModel,
    settingsViewModel: SettingsViewModel,
    statementReconciliationViewModel: StatementReconciliationViewModel,
    notificationCenterViewModel: NotificationCenterViewModel,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onLocaleChanged: () -> Unit,
    onRequestExport: () -> Unit,
    onRequestImport: () -> Unit,
    onRequestExportLogs: () -> Unit,
    onRequestRestoreBackup: () -> Unit,
) {
    val onboardingState by onboardingViewModel.uiState.collectAsState()
    val reviewState by reviewViewModel.uiState.collectAsState()
    val dashboardState by dashboardViewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val notificationState by notificationCenterViewModel.uiState.collectAsState()
    var homeDestination by rememberSaveable { mutableStateOf(HomeDestination.Dashboard) }
    var settingsReturnDestination by rememberSaveable {
        mutableStateOf(HomeDestination.Dashboard)
    }
    var reviewReturnDestination by rememberSaveable {
        mutableStateOf(HomeDestination.Dashboard)
    }
    var pendingSettingsLaunch by remember { mutableStateOf<SettingsLaunchRequest?>(null) }
    var transactionListSeedFilter by remember { mutableStateOf<TransactionListFilterState?>(null) }
    var transactionListReturnDestination by rememberSaveable {
        mutableStateOf(HomeDestination.Dashboard)
    }
    var transactionListOpenGeneration by remember { mutableStateOf(0) }
    var pendingLoansSummaryLoanKey by rememberSaveable { mutableStateOf<String?>(null) }
    var loansSummaryDetailExitsToHome by rememberSaveable { mutableStateOf(false) }
    var pendingCardsSummaryCardKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCardsSummaryDebitKey by rememberSaveable { mutableStateOf<String?>(null) }
    var cardsSummaryDetailExitsToHome by rememberSaveable { mutableStateOf(false) }

    // [launch] replaces the Settings stack so the first back returns to [returnTo].
    fun openSettings(
        returnTo: HomeDestination = HomeDestination.Dashboard,
        launch: SettingsLaunchRequest? = null,
    ) {
        settingsReturnDestination = returnTo
        pendingSettingsLaunch = launch
        homeDestination = HomeDestination.Settings
    }

    fun openReview(returnTo: HomeDestination) {
        reviewReturnDestination = returnTo
        homeDestination = HomeDestination.Review
    }

    fun openTransactionList(
        filter: TransactionListFilterState? = null,
        returnTo: HomeDestination = HomeDestination.Dashboard,
    ) {
        transactionListSeedFilter = filter
        transactionListReturnDestination = returnTo
        transactionListOpenGeneration++
        homeDestination = HomeDestination.AllTransactions
    }

    val notificationExternalState = notificationCenterExternalState(
        dashboardState = dashboardState,
        settingsState = settingsState,
    )

    LaunchedEffect(notificationExternalState) {
        notificationCenterViewModel.refresh(notificationExternalState)
    }

    if (onboardingState.step == OnboardingStep.HOME) {
        BackHandler {
            when {
                dashboardState.selectedTransactionId != null ->
                    dashboardViewModel.closeTransactionDetail()

                homeDestination == HomeDestination.AllTransactions ->
                    homeDestination = transactionListReturnDestination

                homeDestination == HomeDestination.AccountsSummary ->
                    homeDestination = HomeDestination.Dashboard

                homeDestination == HomeDestination.CardsSummary ->
                    homeDestination = HomeDestination.Dashboard

                homeDestination == HomeDestination.LoansSummary ->
                    homeDestination = HomeDestination.Dashboard

                homeDestination == HomeDestination.MerchantsSummary ->
                    homeDestination = HomeDestination.Dashboard

                homeDestination == HomeDestination.NotificationCenter ->
                    homeDestination = HomeDestination.Dashboard

                homeDestination == HomeDestination.Settings -> {
                    homeDestination = settingsReturnDestination
                    dashboardViewModel.refresh()
                }

                homeDestination == HomeDestination.Review ->
                    if (reviewState.selectedDetail != null) {
                        reviewViewModel.closeDetail()
                    } else {
                        homeDestination = reviewReturnDestination
                        if (reviewReturnDestination == HomeDestination.Dashboard) {
                            dashboardViewModel.refresh()
                        }
                    }

                else -> {
                    // Consume system back on the home screen — do not exit the app.
                }
            }
        }

        val detailOpenedFromAllTransactions =
            homeDestination == HomeDestination.AllTransactions &&
                dashboardState.selectedTransactionId != null

        if (!detailOpenedFromAllTransactions) {
            if (showTransactionDetail(
                    dashboardState = dashboardState,
                    onBack = dashboardViewModel::closeTransactionDetail,
                    onReclassify = dashboardViewModel::reclassifySelectedTransaction,
                    onIgnore = dashboardViewModel::ignoreSelectedTransaction,
                    onMarkAsCommitment = dashboardViewModel::markSelectedTransactionAsCommitment,
                )
            ) {
                return
            }
        }

        when (homeDestination) {
            HomeDestination.Dashboard -> DashboardRoute(
                viewModel = dashboardViewModel,
                notificationUnreadCount = notificationState.unreadCount,
                onOpenNotificationCenter = { homeDestination = HomeDestination.NotificationCenter },
                onOpenAllTransactions = { openTransactionList() },
                onOpenTransaction = dashboardViewModel::openTransactionDetail,
                onOpenSettings = { openSettings() },
                onManageCardsInSettings = {
                    openSettings(
                        launch = resolveManageSettingsLaunch(
                            state = settingsState,
                            target = ManageSettingsTarget.Cards,
                        ),
                    )
                },
                onOpenAccountsSummary = {
                    homeDestination = HomeDestination.AccountsSummary
                },
                onOpenCardsSummary = {
                    pendingCardsSummaryCardKey = null
                    pendingCardsSummaryDebitKey = null
                    cardsSummaryDetailExitsToHome = false
                    homeDestination = HomeDestination.CardsSummary
                },
                onOpenLoansSummary = {
                    pendingLoansSummaryLoanKey = null
                    loansSummaryDetailExitsToHome = false
                    homeDestination = HomeDestination.LoansSummary
                },
                onOpenMerchantsSummary = {
                    homeDestination = HomeDestination.MerchantsSummary
                },
                onOpenLoanDetail = { loan ->
                    pendingLoansSummaryLoanKey = LoanOwnershipKey.of(loan)
                    loansSummaryDetailExitsToHome = true
                    homeDestination = HomeDestination.LoansSummary
                },
                onOpenCardDetail = { card ->
                    pendingCardsSummaryCardKey = CardOwnershipKey.of(card)
                    pendingCardsSummaryDebitKey = null
                    cardsSummaryDetailExitsToHome = true
                    homeDestination = HomeDestination.CardsSummary
                },
                onOpenDebitDetail = { debit ->
                    pendingCardsSummaryDebitKey = CardOwnershipKey.of(debit)
                    pendingCardsSummaryCardKey = null
                    cardsSummaryDetailExitsToHome = true
                    homeDestination = HomeDestination.CardsSummary
                },
                onRequestSmsPermission = onRequestPermissions,
                onOpenAppSettings = onOpenAppSettings,
            )
            HomeDestination.AccountsSummary -> AccountsSummaryRoute(
                viewModel = dashboardViewModel,
                onBack = { homeDestination = HomeDestination.Dashboard },
                onManageAccounts = {
                    openSettings(
                        returnTo = HomeDestination.AccountsSummary,
                        launch = resolveManageSettingsLaunch(
                            state = settingsState,
                            target = ManageSettingsTarget.Accounts,
                        ),
                    )
                },
                onOpenTransaction = dashboardViewModel::openTransactionDetail,
                onOpenAllTransactions = { filter ->
                    openTransactionList(
                        filter = filter,
                        returnTo = HomeDestination.AccountsSummary,
                    )
                },
            )
            HomeDestination.CardsSummary -> CardsSummaryRoute(
                viewModel = dashboardViewModel,
                initialSelectedCardKey = pendingCardsSummaryCardKey,
                initialSelectedDebitKey = pendingCardsSummaryDebitKey,
                detailBackExitsSummary = cardsSummaryDetailExitsToHome,
                onInitialSelectionConsumed = {
                    pendingCardsSummaryCardKey = null
                    pendingCardsSummaryDebitKey = null
                },
                onBack = { homeDestination = HomeDestination.Dashboard },
                onManageCards = {
                    openSettings(
                        returnTo = HomeDestination.CardsSummary,
                        launch = resolveManageSettingsLaunch(
                            state = settingsState,
                            target = ManageSettingsTarget.Cards,
                        ),
                    )
                },
                onOpenTransaction = dashboardViewModel::openTransactionDetail,
                onOpenAllTransactions = { filter ->
                    openTransactionList(
                        filter = filter,
                        returnTo = HomeDestination.CardsSummary,
                    )
                },
            )
            HomeDestination.LoansSummary -> LoansSummaryRoute(
                viewModel = dashboardViewModel,
                initialSelectedLoanKey = pendingLoansSummaryLoanKey,
                detailBackExitsSummary = loansSummaryDetailExitsToHome,
                onInitialSelectionConsumed = { pendingLoansSummaryLoanKey = null },
                onBack = { homeDestination = HomeDestination.Dashboard },
                onManageLoans = {
                    openSettings(
                        returnTo = HomeDestination.LoansSummary,
                        launch = resolveManageSettingsLaunch(
                            state = settingsState,
                            target = ManageSettingsTarget.Loans,
                        ),
                    )
                },
                onOpenTransaction = dashboardViewModel::openTransactionDetail,
                onOpenAllTransactions = { filter ->
                    openTransactionList(
                        filter = filter,
                        returnTo = HomeDestination.LoansSummary,
                    )
                },
            )
            HomeDestination.MerchantsSummary -> MerchantSpendingSummaryRoute(
                viewModel = dashboardViewModel,
                onBack = { homeDestination = HomeDestination.Dashboard },
                onOpenTransaction = dashboardViewModel::openTransactionDetail,
                onOpenAllTransactions = { filter ->
                    openTransactionList(
                        filter = filter,
                        returnTo = HomeDestination.MerchantsSummary,
                    )
                },
            )
            HomeDestination.NotificationCenter -> NotificationCenterRoute(
                viewModel = notificationCenterViewModel,
                externalState = notificationExternalState,
                onBack = { homeDestination = HomeDestination.Dashboard },
                onNavigate = { action ->
                    handleNotificationAction(
                        action = action,
                        settingsState = settingsState,
                        onRequestPermissions = onRequestPermissions,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenReview = {
                            openReview(returnTo = HomeDestination.NotificationCenter)
                        },
                        onOpenSettings = { launch ->
                            openSettings(
                                returnTo = HomeDestination.NotificationCenter,
                                launch = launch,
                            )
                        },
                        onDismissRescanStatus = dashboardViewModel::clearRescanStatus,
                    )
                },
            )
            HomeDestination.Review -> ReviewRoute(
                viewModel = reviewViewModel,
                onBack = {
                    homeDestination = reviewReturnDestination
                    if (reviewReturnDestination == HomeDestination.Dashboard) {
                        dashboardViewModel.refresh()
                    }
                },
            )
            HomeDestination.AllTransactions -> {
                val ownedCardKeys = remember(dashboardState.ownedCards) {
                    CardOwnershipKey.ownedKeys(dashboardState.ownedCards)
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    TransactionListScreen(
                        periodLabel = dashboardState.periodLabel,
                        transactions = dashboardState.allTransactions,
                        onBack = { homeDestination = transactionListReturnDestination },
                        onOpenTransaction = dashboardViewModel::openTransactionDetail,
                        seedFilter = transactionListSeedFilter,
                        onSeedFilterApplied = { transactionListSeedFilter = null },
                        openGeneration = transactionListOpenGeneration,
                        ownedCardKeys = ownedCardKeys,
                        ownedAccountContainerIds = dashboardState.ownedAccountContainerIds,
                        ownedCards = dashboardState.ownedCards,
                        ownedAccounts = dashboardState.ownedAccounts,
                        transactionAccountInvolvement = dashboardState.transactionAccountInvolvement,
                    )
                    showTransactionDetail(
                        dashboardState = dashboardState,
                        onBack = dashboardViewModel::closeTransactionDetail,
                        onReclassify = dashboardViewModel::reclassifySelectedTransaction,
                        onIgnore = dashboardViewModel::ignoreSelectedTransaction,
                        onMarkAsCommitment = dashboardViewModel::markSelectedTransactionAsCommitment,
                        overlayOnList = true,
                    )
                }
            }
            HomeDestination.Settings -> SettingsRoute(
                viewModel = settingsViewModel,
                statementReconciliationViewModel = statementReconciliationViewModel,
                reviewRequiredCount = dashboardState.summary?.reviewRequiredCount ?: 0,
                pendingLaunch = pendingSettingsLaunch,
                onPendingLaunchConsumed = { pendingSettingsLaunch = null },
                onBack = {
                    homeDestination = settingsReturnDestination
                    dashboardViewModel.refresh()
                },
                onOpenReview = { openReview(returnTo = HomeDestination.Settings) },
                onLocaleChanged = onLocaleChanged,
                onRequestExport = onRequestExport,
                onRequestImport = onRequestImport,
                onRequestExportLogs = onRequestExportLogs,
                onRequestSmsPermission = onRequestPermissions,
                onOpenAppSettings = onOpenAppSettings,
            )
        }
    } else {
        OnboardingRoute(
            viewModel = onboardingViewModel,
            onRequestPermissions = onRequestPermissions,
            onOpenAppSettings = onOpenAppSettings,
            onRequestRestoreBackup = onRequestRestoreBackup,
        )
    }
}

/**
 * Shows transaction detail when a selection exists.
 *
 * Returns true when the caller should stop composing sibling destinations (full-screen detail).
 * Returns false when nothing is shown, or when detail is rendered as an overlay sibling.
 */
@Composable
private fun showTransactionDetail(
    dashboardState: DashboardUiState,
    onBack: () -> Unit,
    onReclassify: (FinancialTransactionType) -> Unit,
    onIgnore: () -> Unit,
    onMarkAsCommitment: () -> Unit,
    overlayOnList: Boolean = false,
): Boolean {
    val selectedId = dashboardState.selectedTransactionId ?: return false
    val selected = dashboardState.allTransactions.find { it.id == selectedId }
        ?: dashboardState.recentTransactions.find { it.id == selectedId }
    if (selected != null) {
        val detailContent: @Composable () -> Unit = {
            TransactionDetailScreen(
                transaction = selected,
                smsEvidence = dashboardState.selectedTransactionSms,
                smsLoading = dashboardState.selectedTransactionSmsLoading,
                reclassifying = dashboardState.reclassifying,
                reclassifySuccess = dashboardState.reclassifySuccess,
                ignoring = dashboardState.ignoring,
                markingCommitment = dashboardState.markingCommitment,
                isCommitted = selected.id in dashboardState.committedSourceTransactionIds,
                markCommitmentSuccess = dashboardState.markCommitmentSuccess,
                error = dashboardState.reclassifyError ?: dashboardState.ignoreError ?: dashboardState.markCommitmentError,
                onBack = onBack,
                onReclassify = onReclassify,
                onIgnore = onIgnore,
                onMarkAsCommitment = onMarkAsCommitment,
                ownedCards = dashboardState.ownedCards,
            )
        }
        if (overlayOnList) {
            MasroofScreenBackground(modifier = Modifier.fillMaxSize(), content = detailContent)
        } else {
            detailContent()
        }
        return true
    }
    // Keep the detail destination while a refresh reloads rows for the same period.
    return dashboardState.loading
}

private fun handleNotificationAction(
    action: NotificationAction,
    settingsState: com.baraa.masroof.presentation.settings.SettingsUiState,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenSettings: (SettingsLaunchRequest) -> Unit,
    onDismissRescanStatus: () -> Unit,
) {
    when (action) {
        NotificationAction.REQUEST_SMS_PERMISSION -> onRequestPermissions()
        NotificationAction.OPEN_APP_SETTINGS -> onOpenAppSettings()
        NotificationAction.OPEN_REVIEW -> onOpenReview()
        NotificationAction.OPEN_SETTINGS_CARDS,
        NotificationAction.OPEN_SETTINGS_ACCOUNTS,
        NotificationAction.OPEN_SETTINGS_ABOUT,
        -> resolveNotificationSettingsLaunch(action, settingsState)?.let(onOpenSettings)

        NotificationAction.DISMISS_RESCAN -> onDismissRescanStatus()
        NotificationAction.MARK_READ_ONLY -> Unit
    }
}
