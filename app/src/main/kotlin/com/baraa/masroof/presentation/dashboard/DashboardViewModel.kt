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
import com.baraa.masroof.application.dashboard.loadOverview
import com.baraa.masroof.application.dashboard.salaryPeriod
import com.baraa.masroof.application.dashboard.CommitmentsOverview
import com.baraa.masroof.application.dashboard.DashboardLayoutPreferencesRepository
import com.baraa.masroof.application.dashboard.DashboardLayoutSnapshot
import com.baraa.masroof.application.dashboard.DashboardOverviewLoader
import com.baraa.masroof.application.dashboard.DashboardSectionId
import com.baraa.masroof.application.dashboard.DashboardTransactionFacts
import com.baraa.masroof.application.dashboard.TransactionSmsEvidenceLoader
import com.baraa.masroof.application.dashboard.TransactionLocalDate
import com.baraa.masroof.application.transaction.IgnoreResult
import com.baraa.masroof.application.transaction.ReclassificationResult
import com.baraa.masroof.application.transaction.TransactionIgnoreService
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
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
import com.baraa.masroof.presentation.common.launchLogged
import com.baraa.masroof.presentation.locale.AppLocaleContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class DashboardViewModel(
    private val overviewLoader: DashboardOverviewLoader,
    private val dashboardRegistryWorkflow: DashboardRegistryWorkflow,
    private val dashboardPeriodWorkflow: DashboardPeriodWorkflow,
    private val layoutPreferencesRepository: DashboardLayoutPreferencesRepository,
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
    val chartDataWorkflow: com.baraa.masroof.application.dashboard.ChartDataWorkflow =
        com.baraa.masroof.application.dashboard.ChartDataWorkflow(com.baraa.masroof.application.dashboard.InMemoryChartDataPreferencesRepository()),
    val analytics: com.baraa.masroof.application.analytics.AnalyticsWorkflow? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val transactionListWorkflow = com.baraa.masroof.application.dashboard.TransactionListWorkflow()

    fun setTransactionListQuery(query: TransactionListFilterState) {
        _uiState.update { it.copy(transactionListQuery = query).withTransactionListProjection() }
    }

    private fun DashboardUiState.withTransactionListProjection(): DashboardUiState = copy(
        transactionListProjection = TransactionListPresentationMapper.project(
            allTransactions, transactionListQuery, CardOwnershipKey.ownedKeys(ownedCards),
            ownedAccountContainerIds, transactionAccountInvolvement, workflow = transactionListWorkflow,
        ),
    )

    private var activePeriod: DashboardSalaryPeriod = dashboardPeriodWorkflow.currentPeriod()
    private var followCurrentPeriod = true

    private var loadJob: Job? = null

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

    fun moveCustomizeSection(id: DashboardSectionId, direction: Int) {
        mutateCustomizeDraft { snapshot ->
            val sections = snapshot.sections.toMutableList()
            val index = sections.indexOfFirst { it.id == id }
            if (index < 0) return@mutateCustomizeDraft snapshot
            // Step past sections the sheet does not show, so Up/Down swaps with a visible row.
            var target = index + direction
            while (target in sections.indices && sections[target].id in CUSTOMIZE_SHEET_HIDDEN_SECTIONS) target += direction
            if (target !in sections.indices) return@mutateCustomizeDraft snapshot
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
        if (followCurrentPeriod) {
            val current = dashboardPeriodWorkflow.currentPeriod()
            if (current != activePeriod) {
                activePeriod = current
                load(activePeriod, resetSelection = true)
                return
            }
        }
        load(activePeriod)
    }

    /** First load only: a restored visit can open on Cards or Accounts, which read this state but never load it. */
    fun ensureLoaded() {
        if (_uiState.value.period == null) refresh()
    }

    fun onAppResumed() {
        // refresh() re-reads the SMS permission. Granting SMS access does not select banks or authorise a historical import.
        refresh()
    }

    fun goToPreviousPeriod() {
        followCurrentPeriod = false
        activePeriod = dashboardPeriodWorkflow.previous(activePeriod)
        load(activePeriod, resetSelection = true)
    }

    fun goToNextPeriod() {
        followCurrentPeriod = false
        activePeriod = dashboardPeriodWorkflow.next(activePeriod)
        load(activePeriod, resetSelection = true)
    }

    fun goToCurrentPeriod() {
        followCurrentPeriod = true
        activePeriod = dashboardPeriodWorkflow.currentPeriod()
        load(activePeriod, resetSelection = true)
    }

    private fun syncSmsPermissionState() {
        _uiState.update { it.copy(smsPermissionGranted = permissionStateProvider()) }
    }

    fun openTransactionDetail(transactionId: String) {
        _uiState.update {
            it.copy(
                selectedTransactionId = transactionId,
                labSelectedTransaction = null,
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
                labSelectedTransaction = null,
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
            val outsidePeriod = _uiState.value.allTransactions.none { it.id == transactionId } && _uiState.value.recentTransactions.none { it.id == transactionId }
            val labRow = if (!outsidePeriod) null else try {
                analytics?.evidence(listOf(transactionId))?.firstOrNull()?.let { item -> item.transaction?.let { toPreview(it, item.facts) } }
            } catch (ce: CancellationException) { throw ce } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.DASHBOARD, "Dashboard loadSelectedTransactionSms", caught); null }
            val evidence = try {
                smsEvidenceLoader.loadForTransaction(transactionId).map { item ->
                    TransactionSmsEvidenceUi(body = item.body, sender = item.sender, provenance = item.provenance)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.DASHBOARD, "Dashboard loadSelectedTransactionSms", caught);
                emptyList()
            }
            _uiState.update { current ->
                if (current.selectedTransactionId != transactionId) {
                    current
                } else {
                    current.copy(
                        selectedTransactionSms = evidence,
                        labSelectedTransaction = labRow,
                        selectedTransactionSmsLoading = false,
                    )
                }
            }
        }
    }

    fun reclassifySelectedTransaction(newType: FinancialTransactionType) {
        val transactionId = _uiState.value.selectedTransactionId ?: return
        launchLogged(appLogService, AppLogCategories.DASHBOARD, "Dashboard reclassifySelectedTransaction", onError = {
            _uiState.update { it.copy(reclassifying = false, reclassifyError = "update_failed") }
        }) {
            _uiState.update { it.copy(reclassifying = true, reclassifySuccess = false, reclassifyError = null, ignoreError = null) }
            when (val result = reclassificationService.reclassify(transactionId, newType)) {
                is ReclassificationResult.Success -> {
                    refresh()
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
            } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.DASHBOARD, "Dashboard markSelectedTransactionAsCommitment", caught);
                CommitmentCreationResult.Rejected("create_failed")
            }
            when (result) {
                CommitmentCreationResult.Success -> {
                    refresh()
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
                labSelectedTransaction = null,
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

    private fun periodPresentation(period: DashboardSalaryPeriod): Pair<String, String?> {
        val adjustment = dashboardPeriodWorkflow.salaryCycleStartAdjustment(period)
        val context = localizedContext()
        return FinancialPeriodUiFormatter.formatSalaryPeriodTitle(context, period) to
            FinancialPeriodUiFormatter.formatAdjustmentHint(context, adjustment)
    }

    private fun load(period: DashboardSalaryPeriod, resetSelection: Boolean = false) {
        // Period navigation closes the old detail once, before loading. An async
        // overview result must never overwrite a newer open/close/select action.
        if (resetSelection) closeTransactionDetail()
        val samePeriodRefresh =
            _uiState.value.period == period && _uiState.value.summary?.salaryPeriod == period
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
                    ).withTransactionListProjection()
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
                val tag = languageTag
                val formatter = dateFormatter
                val previews = overview.transactions.map { tx ->
                    toPreview(tx = tx, facts = overview.transactionFacts[tx.id], tag = tag, formatter = formatter)
                }
                val (loadedLabel, loadedHint) = periodPresentation(overview.salaryPeriod)
                _uiState.update {
                    it.copy(
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
                        recentTransactions = previews.take(RECENT_TRANSACTION_LIMIT),
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
                    ).withTransactionListProjection()
                }
                _uiState.value.selectedTransactionId?.let(::loadSelectedTransactionSms)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                appLogService?.failure(AppLogCategories.DASHBOARD, "Dashboard load for period ${period.startDate}", e)
                if (period != activePeriod) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = DashboardError.LOAD_FAILED,
                        period = period,
                        periodLabel = periodLabel,
                        periodAdjustmentHint = periodAdjustmentHint,
                    )
                }
            }
        }
    }

    private fun toPreview(
        tx: FinancialTransaction,
        facts: DashboardTransactionFacts?,
        tag: String = languageTag,
        formatter: DateTimeFormatter = dateFormatter,
    ): TransactionPreviewUi {
        val title = tx.merchant?.takeIf { it.isNotBlank() }
            ?: tx.counterparty?.takeIf { it.isNotBlank() }
        val localDate = TransactionLocalDate.of(tx, zoneId)
        val searchText = listOfNotNull(
            tx.merchant?.trim()?.takeIf { it.isNotEmpty() },
            tx.counterparty?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" ").lowercase(Locale.ROOT)
        val effectiveType = facts?.effectiveType ?: tx.type
        return TransactionPreviewUi(
            id = tx.id,
            title = title,
            amount = tx.amount,
            localDate = localDate,
            amountLabel = MoneyUiFormatter.format(tx.amount, tag),
            dateLabel = formatter.format(localDate),
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
                    cardRole = it.cardRole,
                    parentCardLast4 = it.parentCardLast4,
                )
            }
}
