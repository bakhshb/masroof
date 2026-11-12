package com.baraa.masroof.presentation.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import androidx.lifecycle.viewModelScope
import com.baraa.masroof.application.dashboard.DashboardCommitmentsWorkflow
import com.baraa.masroof.application.dashboard.DashboardPeriodWorkflow
import com.baraa.masroof.application.dashboard.DashboardRegistryWorkflow
import com.baraa.masroof.application.dashboard.DashboardSalaryPeriod
import com.baraa.masroof.application.dashboard.loadOverview
import com.baraa.masroof.application.dashboard.salaryPeriod
import com.baraa.masroof.application.dashboard.DashboardLayoutPreferencesRepository
import com.baraa.masroof.application.dashboard.DashboardOverviewLoader
import com.baraa.masroof.application.dashboard.DashboardSectionId
import com.baraa.masroof.application.dashboard.DashboardTransactionFacts
import com.baraa.masroof.application.dashboard.TransactionSmsEvidenceLoader
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
import com.baraa.masroof.presentation.locale.AppLocaleContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    private val customize = DashboardCustomizeActions(_uiState, layoutPreferencesRepository)
    private val transactionDetail = DashboardTransactionDetailActions(
        scope = viewModelScope,
        state = _uiState,
        reclassificationService = reclassificationService,
        ignoreService = ignoreService,
        dashboardCommitmentsWorkflow = dashboardCommitmentsWorkflow,
        smsEvidenceLoader = smsEvidenceLoader,
        analytics = analytics,
        appLogService = appLogService,
        toPreview = { tx, facts -> transactionPreview(tx, facts, zoneId, languageTag, dateFormatter) },
        refresh = ::refresh,
    )

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

    fun openCustomizeSheet() = customize.openCustomizeSheet()

    fun dismissCustomizeSheet() = customize.dismissCustomizeSheet()

    fun saveCustomizeLayout() = customize.saveCustomizeLayout()

    fun toggleCustomizeSection(id: DashboardSectionId) = customize.toggleCustomizeSection(id)

    fun moveCustomizeSection(id: DashboardSectionId, direction: Int) = customize.moveCustomizeSection(id, direction)

    fun toggleCustomizeQuickExpense() = customize.toggleCustomizeQuickExpense()

    fun toggleCustomizeQuickIncome() = customize.toggleCustomizeQuickIncome()

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

    fun openTransactionDetail(transactionId: String) = transactionDetail.openTransactionDetail(transactionId)

    fun closeTransactionDetail() = transactionDetail.closeTransactionDetail()

    fun reclassifySelectedTransaction(newType: FinancialTransactionType) =
        transactionDetail.reclassifySelectedTransaction(newType)

    fun markSelectedTransactionAsCommitment() = transactionDetail.markSelectedTransactionAsCommitment()

    fun ignoreSelectedTransaction() = transactionDetail.ignoreSelectedTransaction()

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
                val loading = current.loadingPeriod(period, periodLabel, periodAdjustmentHint, samePeriodRefresh)
                if (samePeriodRefresh) loading else loading.withTransactionListProjection()
            }

            try {
                val overview = overviewLoader.loadOverview(period)
                val unknownCards = dashboardRegistryWorkflow.unknownCardsUi()
                val ownedCards = dashboardRegistryWorkflow.ownedCardsUi()
                val ownedAccounts = mergeOwnedAccounts(
                    registryAccounts = dashboardRegistryWorkflow.ownedAccountsUi(),
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
                    transactionPreview(tx, overview.transactionFacts[tx.id], zoneId, tag, formatter)
                }
                val (loadedLabel, loadedHint) = periodPresentation(overview.salaryPeriod)
                _uiState.update {
                    it.withOverview(
                        overview, previews, loadedLabel, loadedHint,
                        unknownCards, ownedCards, ownedAccounts, committedIds,
                    ).withTransactionListProjection()
                }
                _uiState.value.selectedTransactionId?.let(transactionDetail::loadSelectedTransactionSms)
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
}
