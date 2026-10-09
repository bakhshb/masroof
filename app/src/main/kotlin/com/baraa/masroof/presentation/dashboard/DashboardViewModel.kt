package com.baraa.masroof.presentation.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import androidx.lifecycle.viewModelScope
import com.baraa.masroof.application.commitment.CommitmentCreationResult
import com.baraa.masroof.application.dashboard.DashboardCommitmentsWorkflow
import com.baraa.masroof.application.dashboard.DashboardPeriodWorkflow
import com.baraa.masroof.application.dashboard.DashboardRegistryWorkflow
import com.baraa.masroof.application.dashboard.DashboardSalaryPeriod
import com.baraa.masroof.application.dashboard.CommitmentsOverview
import com.baraa.masroof.application.dashboard.DashboardLayoutPreferencesRepository
import com.baraa.masroof.application.dashboard.DashboardLayoutSnapshot
import com.baraa.masroof.application.dashboard.DashboardOverviewLoader
import com.baraa.masroof.application.dashboard.DashboardSectionId
import com.baraa.masroof.application.dashboard.DashboardSectionSize
import com.baraa.masroof.application.dashboard.DashboardTransactionFacts
import com.baraa.masroof.application.dashboard.TransactionSmsEvidenceLoader
import com.baraa.masroof.application.transaction.IgnoreResult
import com.baraa.masroof.application.transaction.ReclassificationResult
import com.baraa.masroof.application.transaction.TransactionIgnoreService
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.application.onboarding.HistoricalImportResult
import com.baraa.masroof.application.onboarding.HistoricalImportUserOutcome
import com.baraa.masroof.application.onboarding.userOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.baraa.masroof.presentation.locale.AppLocaleContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class DashboardViewModel(
    private val overviewLoader: DashboardOverviewLoader,
    private val dashboardRegistryWorkflow: DashboardRegistryWorkflow,
    private val dashboardPeriodWorkflow: DashboardPeriodWorkflow,
    private val layoutPreferencesRepository: DashboardLayoutPreferencesRepository,
    private val rescanService: suspend () -> HistoricalImportResult,
    private val reclassificationService: TransactionReclassificationService,
    private val ignoreService: TransactionIgnoreService,
    private val dashboardCommitmentsWorkflow: DashboardCommitmentsWorkflow,
    private val smsEvidenceLoader: TransactionSmsEvidenceLoader,
    private val permissionStateProvider: () -> Boolean,
    private val appContext: Context,
    private val appLocaleRepository: AppLocaleRepository,
    private val appLogService: AppLogService? = null,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    maintenanceCompletions: Flow<Unit> = emptyFlow(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var activePeriod: DashboardSalaryPeriod = dashboardPeriodWorkflow.currentPeriod()

    private var loadJob: Job? = null
    private var rescanJob: Job? = null

    private val languageTag: String
        get() = appLocaleRepository.getLanguageTag()

    private val dateFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern("d MMM", AppLocale.displayLocale(languageTag))

    private fun localizedContext(): Context =
        AppLocaleContext.wrap(appContext, languageTag)

    companion object {
        const val RECENT_TRANSACTION_LIMIT: Int = 5
    }

    init {
        val savedLayout = layoutPreferencesRepository.load()
        _uiState.update { it.copy(dashboardLayout = savedLayout) }
        viewModelScope.launch {
            maintenanceCompletions.collect {
                if (_uiState.value.period != null) refresh()
            }
        }
    }

    fun openCustomizeSheet() {
        _uiState.update {
            it.copy(
                customizeSheetOpen = true,
                customizeDraft = it.dashboardLayout,
            )
        }
    }

    fun dismissCustomizeSheet() {
        _uiState.update {
            it.copy(
                customizeSheetOpen = false,
                customizeDraft = null,
            )
        }
    }

    fun saveCustomizeLayout() {
        val draft = _uiState.value.customizeDraft ?: return
        layoutPreferencesRepository.save(draft)
        _uiState.update {
            it.copy(
                dashboardLayout = draft,
                customizeSheetOpen = false,
                customizeDraft = null,
            )
        }
    }

    fun toggleCustomizeSection(id: DashboardSectionId) {
        mutateCustomizeDraft { snapshot ->
            snapshot.copy(
                sections = snapshot.sections.map { entry ->
                    if (entry.id == id) entry.copy(visible = !entry.visible) else entry
                },
            )
        }
    }

    fun setCustomizeSectionSize(id: DashboardSectionId, size: DashboardSectionSize) {
        mutateCustomizeDraft { snapshot ->
            snapshot.copy(
                sections = snapshot.sections.map { entry ->
                    if (entry.id == id) entry.copy(size = size) else entry
                },
            )
        }
    }

    fun moveCustomizeSection(id: DashboardSectionId, direction: Int) {
        mutateCustomizeDraft { snapshot ->
            val sections = snapshot.sections.toMutableList()
            val index = sections.indexOfFirst { it.id == id }
            if (index < 0) return@mutateCustomizeDraft snapshot
            val target = (index + direction).coerceIn(0, sections.lastIndex)
            if (target == index) return@mutateCustomizeDraft snapshot
            val item = sections.removeAt(index)
            sections.add(target, item)
            snapshot.copy(sections = sections)
        }
    }

    fun toggleCustomizeQuickExpense() {
        mutateCustomizeDraft { snapshot ->
            snapshot.copy(quickExpenseVisible = !snapshot.quickExpenseVisible)
        }
    }

    fun toggleCustomizeQuickIncome() {
        mutateCustomizeDraft { snapshot ->
            snapshot.copy(quickIncomeVisible = !snapshot.quickIncomeVisible)
        }
    }

    private fun mutateCustomizeDraft(transform: (DashboardLayoutSnapshot) -> DashboardLayoutSnapshot) {
        _uiState.update { current ->
            val base = current.customizeDraft ?: current.dashboardLayout
            current.copy(customizeDraft = transform(base))
        }
    }

    fun refresh() {
        syncSmsPermissionState()
        load(activePeriod, preserveSelectionId = _uiState.value.selectedTransactionId)
    }

    /**
     * Pull-to-refresh: re-import inbox SMS when permission is granted, then reload the dashboard.
     */
    fun refreshWithSmsImport() {
        syncSmsPermissionState()
        if (!permissionStateProvider()) {
            refresh()
            return
        }
        if (rescanJob?.isActive == true) return
        rescanJob = viewModelScope.launch {
            _uiState.update { it.copy(rescanning = true, rescanStatus = null) }
            try {
                val result = rescanService()
                _uiState.update {
                    it.copy(
                        rescanning = false,
                        rescanStatus = mapRescanStatus(result),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(rescanning = false, rescanStatus = SmsRescanStatus.FAILED)
                }
            }
            refresh()
        }
    }

    fun onAppResumed() {
        val granted = permissionStateProvider()
        val wasGranted = _uiState.value.smsPermissionGranted
        _uiState.update { it.copy(smsPermissionGranted = granted) }
        if (granted && !wasGranted) {
            rescanSms()
        } else {
            refresh()
        }
    }

    fun clearRescanStatus() {
        _uiState.update { it.copy(rescanStatus = null) }
    }

    fun goToPreviousPeriod() {
        activePeriod = dashboardPeriodWorkflow.previous(activePeriod)
        load(activePeriod)
    }

    fun goToNextPeriod() {
        activePeriod = dashboardPeriodWorkflow.next(activePeriod)
        load(activePeriod)
    }

    fun goToCurrentPeriod() {
        activePeriod = dashboardPeriodWorkflow.currentPeriod()
        load(activePeriod)
    }

    fun rescanSms() {
        syncSmsPermissionState()
        if (!permissionStateProvider()) {
            _uiState.update { it.copy(rescanStatus = SmsRescanStatus.PERMISSION_DENIED) }
            return
        }
        if (rescanJob?.isActive == true) return
        rescanJob = viewModelScope.launch {
            _uiState.update { it.copy(rescanning = true, rescanStatus = null) }
            try {
                val result = rescanService()
                _uiState.update {
                    it.copy(
                        rescanning = false,
                        rescanStatus = mapRescanStatus(result),
                    )
                }
                refresh()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update { it.copy(rescanning = false, rescanStatus = SmsRescanStatus.FAILED) }
            }
        }
    }

    private fun syncSmsPermissionState() {
        _uiState.update { it.copy(smsPermissionGranted = permissionStateProvider()) }
    }

    private fun mapRescanStatus(result: HistoricalImportResult): SmsRescanStatus =
        when (result.userOutcome()) {
            HistoricalImportUserOutcome.PERMISSION_DENIED -> SmsRescanStatus.PERMISSION_DENIED
            HistoricalImportUserOutcome.FAILED -> SmsRescanStatus.FAILED
            HistoricalImportUserOutcome.NO_MESSAGES -> SmsRescanStatus.NO_MESSAGES
            HistoricalImportUserOutcome.NO_BANK_SMS -> SmsRescanStatus.NO_BANK_SMS
            HistoricalImportUserOutcome.OK -> SmsRescanStatus.OK
            HistoricalImportUserOutcome.ALREADY_UP_TO_DATE -> SmsRescanStatus.ALREADY_UP_TO_DATE
            HistoricalImportUserOutcome.NEEDS_REVIEW -> SmsRescanStatus.NEEDS_REVIEW
            HistoricalImportUserOutcome.NO_NEW_TRANSACTIONS -> SmsRescanStatus.NO_TRANSACTIONS
        }

    fun openTransactionDetail(transactionId: String) {
        _uiState.update {
            it.copy(
                selectedTransactionId = transactionId,
                selectedTransactionSms = emptyList(),
                selectedTransactionSmsLoading = true,
                reclassifySuccess = false,
                reclassifyError = null,
                ignoring = false,
                ignoreError = null,
                markingCommitment = false,
                markCommitmentError = null,
                markCommitmentSuccess = false,
            )
        }
        loadSelectedTransactionSms(transactionId)
    }

    fun closeTransactionDetail() {
        _uiState.update {
            it.copy(
                selectedTransactionId = null,
                selectedTransactionSms = emptyList(),
                selectedTransactionSmsLoading = false,
                reclassifying = false,
                reclassifySuccess = false,
                reclassifyError = null,
                ignoring = false,
                ignoreError = null,
                markingCommitment = false,
                markCommitmentError = null,
                markCommitmentSuccess = false,
            )
        }
    }

    private fun loadSelectedTransactionSms(transactionId: String) {
        viewModelScope.launch {
            val evidence = try {
                smsEvidenceLoader.loadForTransaction(transactionId).map { item ->
                    TransactionSmsEvidenceUi(body = item.body, sender = item.sender)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyList()
            }
            _uiState.update { current ->
                if (current.selectedTransactionId != transactionId) {
                    current
                } else {
                    current.copy(
                        selectedTransactionSms = evidence,
                        selectedTransactionSmsLoading = false,
                    )
                }
            }
        }
    }

    fun reclassifySelectedTransaction(newType: FinancialTransactionType) {
        val transactionId = _uiState.value.selectedTransactionId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(reclassifying = true, reclassifySuccess = false, reclassifyError = null, ignoreError = null) }
            when (val result = reclassificationService.reclassify(transactionId, newType)) {
                is ReclassificationResult.Success -> {
                    refreshPreservingSelection()
                    _uiState.update {
                        it.copy(
                            reclassifying = false,
                            reclassifySuccess = true,
                            reclassifyError = null,
                        )
                    }
                }
                is ReclassificationResult.Rejected -> {
                    _uiState.update {
                        it.copy(
                            reclassifying = false,
                            reclassifyError = result.reason,
                        )
                    }
                }
            }
        }
    }

    fun markSelectedTransactionAsCommitment() {
        val transactionId = _uiState.value.selectedTransactionId ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    markingCommitment = true,
                    markCommitmentError = null,
                    markCommitmentSuccess = false,
                )
            }
            val result = try {
                dashboardCommitmentsWorkflow.createFromTransaction(transactionId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                CommitmentCreationResult.Rejected("create_failed")
            }
            when (result) {
                CommitmentCreationResult.Success -> {
                    refreshPreservingSelection()
                    _uiState.update {
                        it.copy(
                            markingCommitment = false,
                            markCommitmentSuccess = true,
                            markCommitmentError = null,
                            committedSourceTransactionIds = it.committedSourceTransactionIds + transactionId,
                        )
                    }
                }
                CommitmentCreationResult.AlreadyExists -> {
                    _uiState.update {
                        it.copy(
                            markingCommitment = false,
                            markCommitmentError = "already_exists",
                            committedSourceTransactionIds = it.committedSourceTransactionIds + transactionId,
                        )
                    }
                }
                is CommitmentCreationResult.Rejected -> {
                    _uiState.update {
                        it.copy(
                            markingCommitment = false,
                            markCommitmentError = result.reason,
                        )
                    }
                }
            }
        }
    }

    fun ignoreSelectedTransaction() {
        val transactionId = _uiState.value.selectedTransactionId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(ignoring = true, ignoreError = null, reclassifyError = null) }
            when (val result = ignoreService.ignore(transactionId)) {
                is IgnoreResult.Success -> {
                    refresh()
                    _uiState.update {
                        it.copy(
                            selectedTransactionId = null,
                            selectedTransactionSms = emptyList(),
                            selectedTransactionSmsLoading = false,
                            ignoring = false,
                            ignoreError = null,
                        )
                    }
                }
                is IgnoreResult.Rejected -> {
                    _uiState.update {
                        it.copy(
                            ignoring = false,
                            ignoreError = result.reason,
                        )
                    }
                }
            }
        }
    }

    private fun refreshPreservingSelection() {
        refresh()
    }

    private fun periodPresentation(period: DashboardSalaryPeriod): Pair<String, String?> {
        val adjustment = dashboardPeriodWorkflow.salaryCycleStartAdjustment(period)
        val context = localizedContext()
        return FinancialPeriodUiFormatter.formatSalaryPeriodTitle(context, period) to
            FinancialPeriodUiFormatter.formatAdjustmentHint(context, adjustment)
    }

    private fun load(period: DashboardSalaryPeriod, preserveSelectionId: String? = null) {
        val samePeriodRefresh =
            _uiState.value.period == period && _uiState.value.summary?.period == period
        val (periodLabel, periodAdjustmentHint) = periodPresentation(period)

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { current ->
                if (samePeriodRefresh) {
                    current.copy(
                        loading = true,
                        error = null,
                        period = period,
                        periodLabel = periodLabel,
                        periodAdjustmentHint = periodAdjustmentHint,
                    )
                } else {
                    current.copy(
                        loading = true,
                        error = null,
                        period = period,
                        periodLabel = periodLabel,
                        periodAdjustmentHint = periodAdjustmentHint,
                        summary = null,
                        currentAccount = null,
                        spendingSplit = null,
                        creditFacilities = null,
                        loansOverview = null,
                        commitmentsOverview = CommitmentsOverview.empty(),
                        merchantSpending = com.baraa.masroof.application.dashboard.MerchantSpendingOverview.empty(),
                        dailySpendingTrend = null,
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
            }

            try {
                val overview = overviewLoader.loadOverview(period)
                val unknownCards = loadUnknownCards()
                val ownedCards = loadOwnedCards()
                val ownedAccounts = mergeOwnedAccounts(
                    registryAccounts = loadOwnedAccounts(),
                    periodSummaries = overview.ownedAccountPeriodSummaries,
                )
                val committedIds = dashboardCommitmentsWorkflow.committedSourceTransactionIds()
                ensureActive()
                if (period != activePeriod) {
                    return@launch
                }
                val previews = overview.transactions.map { tx ->
                    toPreview(tx = tx, facts = overview.transactionFacts[tx.id])
                }
                val (loadedLabel, loadedHint) = periodPresentation(overview.period)
                _uiState.update {
                    it.copy(
                        loading = false,
                        period = overview.period,
                        periodLabel = loadedLabel,
                        periodAdjustmentHint = loadedHint,
                        summary = overview.summary,
                        currentAccount = overview.currentAccount,
                        spendingSplit = overview.spendingSplit,
                        creditFacilities = overview.creditFacilities,
                        loansOverview = overview.loansOverview,
                        commitmentsOverview = overview.commitmentsOverview,
                        merchantSpending = overview.merchantSpending,
                        dailySpendingTrend = overview.dailySpendingTrend,
                        bankHierarchy = overview.bankHierarchy,
                        accountsFleet = overview.accountsFleet,
                        recentTransactions = previews.take(RECENT_TRANSACTION_LIMIT),
                        allTransactions = previews,
                        flowDetailGrouping = overview.flowDetailGrouping,
                        transactionAccountInvolvement = overview.transactionAccountInvolvement,
                        transactionCardInvolvement = overview.transactionCardInvolvement,
                        transactionLoanInvolvement = overview.transactionLoanInvolvement,
                        transactionDebitSpendInvolvement = overview.transactionDebitSpendInvolvement,
                        isCurrentPeriod = overview.isCurrentPeriod,
                        error = null,
                        selectedTransactionId = preserveSelectionId,
                        unknownCards = unknownCards,
                        ownedCards = ownedCards,
                        ownedAccounts = ownedAccounts,
                        ownedAccountContainerIds = overview.ownedAccountContainerIds,
                        committedSourceTransactionIds = committedIds,
                    )
                }
                preserveSelectionId?.let(::loadSelectedTransactionSms)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                appLogService?.error(AppLogCategories.DASHBOARD, "Dashboard load failed for period ${period.startDate}")
                if (period != activePeriod) {
                    return@launch
                }
                _uiState.update { current ->
                    if (samePeriodRefresh) {
                        current.copy(
                            loading = false,
                            error = DashboardError.LOAD_FAILED,
                            period = period,
                            periodLabel = periodLabel,
                            periodAdjustmentHint = periodAdjustmentHint,
                        )
                    } else {
                        current.copy(
                            loading = false,
                            error = DashboardError.LOAD_FAILED,
                            period = period,
                            periodLabel = periodLabel,
                            periodAdjustmentHint = periodAdjustmentHint,
                            summary = null,
                            currentAccount = null,
                            spendingSplit = null,
                            creditFacilities = null,
                        merchantSpending = com.baraa.masroof.application.dashboard.MerchantSpendingOverview.empty(),
                        dailySpendingTrend = null,
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
                }
            }
        }
    }

    private fun toPreview(
        tx: FinancialTransaction,
        facts: DashboardTransactionFacts?,
    ): TransactionPreviewUi {
        val title = tx.merchant?.takeIf { it.isNotBlank() }
            ?: tx.counterparty?.takeIf { it.isNotBlank() }
        val localDate = tx.occurredAt.atZone(zoneId).toLocalDate()
        val searchText = listOfNotNull(
            tx.merchant?.trim()?.takeIf { it.isNotEmpty() },
            tx.counterparty?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" ").lowercase(Locale.getDefault())
        val effectiveType = facts?.effectiveType ?: tx.type
        return TransactionPreviewUi(
            id = tx.id,
            title = title,
            amount = tx.amount,
            localDate = localDate,
            amountLabel = MoneyUiFormatter.format(tx.amount, languageTag),
            dateLabel = dateFormatter.format(localDate),
            type = tx.type,
            typeLabelResHint = effectiveType,
            direction = TransactionTypePresentation.direction(effectiveType),
            cardLast4 = facts?.primaryCardLast4,
            sourceContainerId = tx.sourceContainerId,
            destinationContainerId = tx.destinationContainerId,
            searchText = searchText,
            sarEquivalent = facts?.sarEquivalent,
            appliedExchangeRate = tx.appliedExchangeRate,
            exchangeRateSource = tx.exchangeRateSource,
        )
    }

    private suspend fun loadUnknownCards(): List<UnknownCardCandidateUi> =
        dashboardRegistryWorkflow.listUnknownCards()
            .map { UnknownCardCandidateUi(bank = it.bank, last4 = it.last4) }

    private suspend fun loadOwnedAccounts(): List<OwnedAccountUi> =
        dashboardRegistryWorkflow.listOwnedAccounts()
            .map {
                OwnedAccountUi(
                    bank = it.bank,
                    maskedNumber = it.maskedNumber,
                    displayName = it.displayName,
                )
            }

    private fun mergeOwnedAccounts(
        registryAccounts: List<OwnedAccountUi>,
        periodSummaries: List<com.baraa.masroof.application.dashboard.OwnedAccountPeriodSummary>,
    ): List<OwnedAccountUi> {
        val netsByKey = periodSummaries.associateBy { "${it.bank.id}:${it.maskedNumber}" }
        return registryAccounts.map { account ->
            val summary = netsByKey["${account.bank.id}:${account.maskedNumber}"]
            account.copy(periodSummary = summary?.summary)
        }
    }

    private suspend fun loadOwnedCards(): List<OwnedCardUi> =
        dashboardRegistryWorkflow.listOwnedCards()
            .map {
                OwnedCardUi(
                    bank = it.bank,
                    last4 = it.last4,
                    displayName = it.displayName,
                    cardNetwork = it.cardNetwork,
                )
            }
}
