package com.baraa.masroof.presentation.review

import com.baraa.masroof.presentation.common.launchLogged
import com.baraa.masroof.application.logging.AppLogCategories
import androidx.lifecycle.ViewModel
import com.baraa.masroof.application.locale.AppLocaleRepository
import androidx.lifecycle.viewModelScope
import com.baraa.masroof.application.review.ExternalTransferDirection
import com.baraa.masroof.application.review.ReviewDetailLoader
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.application.review.ReviewOwnershipWorkflow
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ReviewStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

class ReviewViewModel(
    private val reviewWorkflowService: ReviewWorkflowService,
    private val detailLoader: ReviewDetailLoader,
    private val reviewOwnershipWorkflow: ReviewOwnershipWorkflow,
    private val transactionRestoreService: TransactionRestoreService,
    private val reparseStoredSms: suspend (String) -> Unit,
    private val selectableBanks: List<Bank> = emptyList(),
    private val selectBankAndReparse: suspend (rawSmsId: String, bankId: String) -> Unit = { _, _ -> },
    private val appLocaleRepository: AppLocaleRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    maintenanceCompletions: Flow<Unit> = emptyFlow(),
    val messagePatternsWorkflow: com.baraa.masroof.application.patterns.MessagePatternsWorkflow? = null,
    private val appLogService: com.baraa.masroof.application.logging.AppLogService? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private var refreshRequested = false
    private var refreshJob: Job? = null

    private val mapper = ReviewPresentationMapper(reviewWorkflowService, reviewOwnershipWorkflow, selectableBanks, appLocaleRepository, zoneId)
    private val loads = ReviewLoads(_uiState, detailLoader, mapper)
    private val actions = ReviewResolveActions(
        scope = viewModelScope,
        state = _uiState,
        loads = loads,
        mapper = mapper,
        reviewWorkflowService = reviewWorkflowService,
        detailLoader = detailLoader,
        reviewOwnershipWorkflow = reviewOwnershipWorkflow,
        transactionRestoreService = transactionRestoreService,
        selectBankAndReparse = selectBankAndReparse,
        appLogService = appLogService,
    )

    init {
        viewModelScope.launch {
            maintenanceCompletions.collect {
                if (refreshRequested) refresh()
            }
        }
    }

    fun refresh() {
        refreshRequested = true
        // A slower load for the previous tab must not overwrite the current one.
        refreshJob?.cancel()
        refreshJob = launchLogged(appLogService, AppLogCategories.REVIEW, "Review refresh", onError = { _uiState.update { it.copy(loading = false, error = ReviewError.LOAD_FAILED) } }) {
            _uiState.update { it.copy(loading = true, error = null) }
            val summaries = loads.loadCurrentSummaries()
            loads.applySummaries(summaries)
        }
    }

    fun setListMode(mode: ReviewListMode) {
        if (_uiState.value.listMode == mode) return
        _uiState.update {
            it.copy(
                listMode = mode,
                items = emptyList(),
                informationalDismissCount = 0,
                loading = true,
                error = null,
                message = null,
            )
        }
        refresh()
    }

    /** The detail the user asked for last; an older load that finishes later is discarded. Main thread only. */
    private var requestedDetailId: String? = null

    fun openDetail(reviewId: String) {
        requestedDetailId = reviewId
        // Not cancelled when another detail opens: the missing-amount reparse below writes financial state.
        // A newer request owns the loading flag; with none pending (detail closed), clear it.
        fun current() = (requestedDetailId == reviewId).also { latest ->
            if (!latest && requestedDetailId == null) _uiState.update { it.copy(loading = false) }
        }
        launchLogged(appLogService, AppLogCategories.REVIEW, "Review openDetail", onError = {
            if (current()) _uiState.update { it.copy(loading = false, error = ReviewError.LOAD_FAILED) }
        }) {
            _uiState.update { it.copy(loading = true, error = null, message = null) }
            var detail = detailLoader.loadDetail(reviewId) ?: run {
                if (current()) _uiState.update { it.copy(loading = false, error = ReviewError.LOAD_FAILED) }
                return@launchLogged
            }
            if (detail.review.reasons.contains("missing_amount")) {
                reparseStoredSms(detail.review.rawSmsId)
                detail = detailLoader.loadDetail(reviewId) ?: run {
                    if (current()) _uiState.update { it.copy(loading = false, error = ReviewError.LOAD_FAILED) }
                    return@launchLogged
                }
            }
            val pairCandidates = loads.pairCandidatesFor(detail)
            if (!current()) return@launchLogged
            _uiState.update {
                it.copy(
                    loading = false,
                    selectedDetail = mapper.toDetailUi(detail, pairCandidates),
                    error = null,
                )
            }
        }
    }

    fun closeDetail() {
        requestedDetailId = null
        _uiState.update { it.copy(selectedDetail = null, message = null, error = null, actionErrorDetail = null) }
    }

    /** Reload saved facts after teaching; returning never triggers financial reconciliation. */
    fun refreshAfterPatternTeaching() {
        val id = _uiState.value.selectedDetail?.id ?: return
        launchLogged(appLogService, AppLogCategories.REVIEW, "Review refreshAfterPatternTeaching", onError = { _uiState.update { it.copy(error = ReviewError.LOAD_FAILED) } }) {
            val detail = detailLoader.loadDetail(id)
            val resolved = detail?.review?.status == ReviewStatus.RESOLVED
            val updated = if (detail == null || resolved) null else mapper.toDetailUi(detail, loads.pairCandidatesFor(detail))
            loads.applySummaries(loads.loadCurrentSummaries())
            _uiState.update {
                if (it.selectedDetail?.id != id) it
                else it.copy(selectedDetail = updated, message = if (resolved) ReviewMessage.RESOLVED else null)
            }
        }
    }
    fun dismissAllInformational() = actions.dismissAllInformational()

    fun resolveAsFinancialType(type: FinancialTransactionType) = actions.resolveAsFinancialType(type)

    fun resolveAsExternalTransfer(reviewId: String, direction: ExternalTransferDirection) =
        actions.resolveAsExternalTransfer(reviewId, direction)

    fun resolveAsIgnored() = actions.resolveAsIgnored()

    fun selectBank(bankId: String) = actions.selectBank(bankId)

    fun resolveAsNonFinancial() = actions.resolveAsNonFinancial()

    fun restoreIgnoredMessage(newType: FinancialTransactionType? = null) = actions.restoreIgnoredMessage(newType)

    fun confirmOwnershipCardOwned() = actions.confirmOwnershipCardOwned()

    fun markOwnershipCardExternal() = actions.markOwnershipCardExternal()

    fun resolveSelfTransferPair(partnerReviewId: String) = actions.resolveSelfTransferPair(partnerReviewId)
}
