package com.baraa.masroof.presentation.review

import com.baraa.masroof.presentation.common.launchLogged
import com.baraa.masroof.application.logging.AppLogCategories
import androidx.lifecycle.ViewModel
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import androidx.lifecycle.viewModelScope
import com.baraa.masroof.application.review.ExternalTransferDirection
import com.baraa.masroof.application.review.ReviewDetailLoader
import com.baraa.masroof.application.review.ReviewWorkflowResult
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.transaction.RestoreResult
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.application.review.ReviewOwnershipWorkflow
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.ExplicitBankSelection
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.presentation.dashboard.MoneyUiFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    init {
        viewModelScope.launch {
            maintenanceCompletions.collect {
                if (refreshRequested) refresh()
            }
        }
    }

    private val languageTag: String
        get() = appLocaleRepository.getLanguageTag()

    private val dateFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern(
            "d MMM yyyy",
            AppLocale.displayLocale(languageTag),
        )

    fun refresh() {
        refreshRequested = true
        // A slower load for the previous tab must not overwrite the current one.
        refreshJob?.cancel()
        refreshJob = launchLogged(appLogService, AppLogCategories.REVIEW, "Review refresh", onError = { _uiState.update { it.copy(loading = false, error = ReviewError.LOAD_FAILED) } }) {
            _uiState.update { it.copy(loading = true, error = null) }
            val summaries = loadCurrentSummaries()
            applySummaries(summaries)
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

    fun dismissAllInformational() {
        launchLogged(appLogService, AppLogCategories.REVIEW, "Review dismissAllInformational", onError = { _uiState.update { it.copy(resolving = false, error = ReviewError.ACTION_FAILED) } }) {
            _uiState.update { it.copy(resolving = true, error = null, message = null, actionErrorDetail = null) }
            val summaries = detailLoader.loadSummaries()
            val dismissible = summaries.filter { summary ->
                shouldOfferNonFinancialDismiss(
                    messageFamily = summary.messageFamily,
                    reasons = summary.review.reasons,
                )
            }
            var dismissed = 0
            for (summary in dismissible) {
                when (reviewWorkflowService.resolveAsNonFinancial(summary.review.id)) {
                    is ReviewWorkflowResult.Success -> dismissed++
                    is ReviewWorkflowResult.Rejected -> Unit
                }
            }
            applySummaries(detailLoader.loadSummaries())
            _uiState.update {
                it.copy(
                    resolving = false,
                    message = if (dismissed > 0) ReviewMessage.RESOLVED else it.message,
                    error = if (dismissed == 0 && dismissible.isNotEmpty()) ReviewError.ACTION_FAILED else null,
                )
            }
        }
    }

    /** The detail the user asked for last; an older load that finishes later is discarded. Main thread only. */
    private var requestedDetailId: String? = null

    fun openDetail(reviewId: String) {
        requestedDetailId = reviewId
        // Not cancelled when another detail opens: the missing-amount reparse below writes financial state.
        viewModelScope.launch {
            // A newer request owns the loading flag; with none pending (detail closed), clear it.
            fun current() = (requestedDetailId == reviewId).also { latest ->
                if (!latest && requestedDetailId == null) _uiState.update { it.copy(loading = false) }
            }
            _uiState.update { it.copy(loading = true, error = null, message = null) }
            try {
                var detail = detailLoader.loadDetail(reviewId) ?: run {
                    if (current()) _uiState.update { it.copy(loading = false, error = ReviewError.LOAD_FAILED) }
                    return@launch
                }
                if (detail.review.reasons.contains("missing_amount")) {
                    reparseStoredSms(detail.review.rawSmsId)
                    detail = detailLoader.loadDetail(reviewId) ?: run {
                        if (current()) _uiState.update { it.copy(loading = false, error = ReviewError.LOAD_FAILED) }
                        return@launch
                    }
                }
                val pairCandidates = pairCandidatesFor(detail)
                if (!current()) return@launch
                _uiState.update {
                    it.copy(
                        loading = false,
                        selectedDetail = toDetailUi(detail, pairCandidates),
                        error = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.REVIEW, "Review openDetail", caught);
                if (current()) _uiState.update { it.copy(loading = false, error = ReviewError.LOAD_FAILED) }
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
            val updated = if (detail == null || resolved) null else toDetailUi(detail, pairCandidatesFor(detail))
            applySummaries(loadCurrentSummaries())
            _uiState.update {
                if (it.selectedDetail?.id != id) it
                else it.copy(selectedDetail = updated, message = if (resolved) ReviewMessage.RESOLVED else null)
            }
        }
    }

    fun resolveAsFinancialType(type: FinancialTransactionType) {
        val reviewId = _uiState.value.selectedDetail?.id ?: return
        runAction {
            reviewWorkflowService.resolveAsFinancialType(reviewId, type)
        }
    }

    fun resolveAsExternalTransfer(reviewId: String, direction: ExternalTransferDirection) {
        if (_uiState.value.selectedDetail?.id != reviewId) return
        runAction {
            reviewWorkflowService.resolveTransferAsExternal(reviewId, direction)
        }
    }

    fun resolveAsIgnored() {
        resolveAsNonFinancial()
    }

    fun selectBank(bankId: String) {
        val reviewId = _uiState.value.selectedDetail?.id ?: return
        val rawSmsId = _uiState.value.selectedDetail?.rawSmsId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(resolving = true, error = null, message = null, actionErrorDetail = null) }
            try {
                selectBankAndReparse(rawSmsId, bankId)
                val detail = detailLoader.loadDetail(reviewId)
                if (detail == null || detail.review.status == ReviewStatus.RESOLVED) {
                    refreshAfterAction(message = ReviewMessage.RESOLVED, closeDetail = true)
                    return@launch
                }
                val pairCandidates = pairCandidatesFor(detail)
                val stillChoosing = ExplicitBankSelection.offersBankChoice(detail.review.reasons)
                _uiState.update {
                    it.copy(
                        loading = false,
                        resolving = false,
                        selectedDetail = toDetailUi(detail, pairCandidates),
                        message = if (stillChoosing) ReviewMessage.STILL_NEEDS_REVIEW else null,
                        error = null,
                    )
                }
                applySummaries(detailLoader.loadSummaries())
            } catch (ce: CancellationException) {
                throw ce
            } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.REVIEW, "Review selectBank", caught);
                _uiState.update { it.copy(resolving = false, error = ReviewError.ACTION_FAILED) }
            }
        }
    }

    fun resolveAsNonFinancial() {
        val reviewId = _uiState.value.selectedDetail?.id ?: return
        runAction {
            reviewWorkflowService.resolveAsNonFinancial(reviewId)
        }
    }

    fun restoreIgnoredMessage(newType: FinancialTransactionType? = null) {
        val rawSmsId = _uiState.value.selectedDetail?.rawSmsId ?: return
        launchLogged(appLogService, AppLogCategories.REVIEW, "Review restoreIgnoredMessage", onError = {
            _uiState.update {
                it.copy(resolving = false, error = ReviewError.ACTION_FAILED, actionErrorDetail = null)
            }
        }) {
            _uiState.update { it.copy(resolving = true, error = null, message = null, actionErrorDetail = null) }
            when (val result = transactionRestoreService.restore(rawSmsId, newType)) {
                is RestoreResult.Success, RestoreResult.AwaitingReview -> {
                    val summaries = loadCurrentSummaries()
                    applySummaries(summaries)
                    _uiState.update {
                        it.copy(
                            loading = false,
                            resolving = false,
                            selectedDetail = null,
                            message = ReviewMessage.RESTORED,
                            error = null,
                        )
                    }
                }
                is RestoreResult.Rejected -> {
                    _uiState.update {
                        it.copy(
                            resolving = false,
                            error = ReviewError.ACTION_FAILED,
                            actionErrorDetail = result.reason,
                        )
                    }
                }
            }
        }
    }

    fun confirmOwnershipCardOwned() {
        val cardRef = currentOwnershipCardRef() ?: return
        runOwnershipAction(owned = true, cardRef = cardRef)
    }

    fun markOwnershipCardExternal() {
        val cardRef = currentOwnershipCardRef() ?: return
        runOwnershipAction(owned = false, cardRef = cardRef)
    }

    private fun currentOwnershipCardRef(): CardReference? =
        _uiState.value.selectedDetail?.ownershipCard

    private fun runOwnershipAction(owned: Boolean, cardRef: CardReference) {
        viewModelScope.launch {
            _uiState.update { it.copy(resolving = true, error = null, message = null, actionErrorDetail = null) }
            try {
                if (owned) {
                    reviewOwnershipWorkflow.confirmCardOwned(cardRef)
                } else {
                    reviewOwnershipWorkflow.markCardExternal(cardRef)
                }
                reviewWorkflowService.reconcileOwnershipChange(
                    ReviewWorkflowService.OwnershipChange.Card(cardRef),
                )
                val reviewId = _uiState.value.selectedDetail?.id
                if (reviewId != null) {
                    val detail = detailLoader.loadDetail(reviewId)
                    if (detail == null) {
                        refreshAfterAction(
                            message = ReviewMessage.RESOLVED,
                            closeDetail = true,
                        )
                        return@launch
                    }
                    val pairCandidates = pairCandidatesFor(detail)
                    _uiState.update {
                        it.copy(
                            loading = false,
                            resolving = false,
                            selectedDetail = toDetailUi(detail, pairCandidates),
                            message = ReviewMessage.RESOLVED,
                            error = null,
                        )
                    }
                    applySummaries(detailLoader.loadSummaries())
                } else {
                    applySummaries(detailLoader.loadSummaries())
                    _uiState.update { it.copy(resolving = false, message = ReviewMessage.RESOLVED) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.REVIEW, "Review runOwnershipAction", caught);
                _uiState.update { it.copy(resolving = false, error = ReviewError.ACTION_FAILED) }
            }
        }
    }

    fun resolveSelfTransferPair(partnerReviewId: String) {
        val currentId = _uiState.value.selectedDetail?.id ?: return
        launchLogged(appLogService, AppLogCategories.REVIEW, "Review resolveSelfTransferPair", onError = { _uiState.update { it.copy(resolving = false, error = ReviewError.ACTION_FAILED) } }) {
            val detail = detailLoader.loadDetail(currentId) ?: return@launchLogged
            val (outgoingId, incomingId) = when (detail.messageFamily) {
                MessageFamily.TRANSFER_OUT -> currentId to partnerReviewId
                MessageFamily.TRANSFER_IN -> partnerReviewId to currentId
                else -> return@launchLogged
            }
            runAction {
                reviewWorkflowService.resolveSelfTransferPair(outgoingId, incomingId)
            }
        }
    }

    private fun runAction(action: suspend () -> ReviewWorkflowResult) {
        launchLogged(appLogService, AppLogCategories.REVIEW, "Review runAction", onError = {
            _uiState.update {
                it.copy(resolving = false, error = ReviewError.ACTION_FAILED, actionErrorDetail = null)
            }
        }) {
            _uiState.update { it.copy(resolving = true, error = null, message = null, actionErrorDetail = null) }
            when (val result = action()) {
                is ReviewWorkflowResult.Success -> {
                    val resolved = result.review.status == ReviewStatus.RESOLVED
                    refreshAfterAction(
                        message = if (resolved) ReviewMessage.RESOLVED else ReviewMessage.STILL_NEEDS_REVIEW,
                        closeDetail = resolved,
                    )
                }
                is ReviewWorkflowResult.Rejected -> {
                    if (result.reason == "transfer_evidence_changed") {
                        val detail = _uiState.value.selectedDetail?.let { detailLoader.loadDetail(it.id) }
                        val candidates = detail?.let { pairCandidatesFor(it) }.orEmpty()
                        val updated = detail?.let { toDetailUi(it, candidates) }
                        _uiState.update { it.copy(selectedDetail = updated) }
                    }
                    _uiState.update {
                        it.copy(
                            resolving = false,
                            error = ReviewError.ACTION_FAILED,
                            actionErrorDetail = result.reason,
                        )
                    }
                }
            }
        }
    }

    private suspend fun refreshAfterAction(
        message: ReviewMessage,
        closeDetail: Boolean,
    ) {
        val summaries = loadCurrentSummaries()
        val items = summaries.map(::toListItem)
        _uiState.update {
            it.copy(
                loading = false,
                resolving = false,
                items = items,
                informationalDismissCount = items.count { item -> item.dismissibleAsNonFinancial },
                selectedDetail = if (closeDetail) null else it.selectedDetail,
                message = message,
                error = null,
            )
        }
    }

    private suspend fun loadCurrentSummaries(): List<ReviewDetailLoader.ReviewSummary> =
        when (_uiState.value.listMode) {
            ReviewListMode.PENDING -> detailLoader.loadSummaries()
            ReviewListMode.IGNORED -> detailLoader.loadIgnoredSummaries()
        }

    private suspend fun pairCandidatesFor(detail: ReviewDetailLoader.ReviewDetail): List<ReviewListItemUi> =
        if (detail.review.kind == ReviewKind.PENDING_MATCH) {
            detailLoader.loadPairCandidates(detail.review.id).map(::toListItem)
        } else {
            emptyList()
        }

    private fun applySummaries(summaries: List<ReviewDetailLoader.ReviewSummary>) {
        val items = summaries.map(::toListItem)
        _uiState.update {
            it.copy(
                loading = false,
                items = items,
                informationalDismissCount = items.count { item -> item.dismissibleAsNonFinancial },
                error = null,
            )
        }
    }

    private fun toListItem(summary: ReviewDetailLoader.ReviewSummary): ReviewListItemUi {
        val review = summary.review
        val dateLabel = when {
            isIgnoredReview(review) ->
                review.resolvedAt?.atZone(zoneId)?.toLocalDate()?.let(dateFormatter::format) ?: "—"
            else ->
                summary.receivedAt?.atZone(zoneId)?.toLocalDate()?.let(dateFormatter::format) ?: "—"
        }
        val dismissible = shouldOfferNonFinancialDismiss(
            messageFamily = summary.messageFamily,
            reasons = review.reasons,
        )
        return ReviewListItemUi(
            id = review.id,
            kind = review.kind,
            kindLabelRes = if (isIgnoredReview(review)) {
                com.baraa.masroof.R.string.review_status_ignored
            } else {
                review.kind.toUiLabelRes()
            },
            title = summary.title ?: "—",
            titleRes = if (summary.title != null) null
                else if (summary.messageFamily in setOf(MessageFamily.PURCHASE, MessageFamily.REFUND)) com.baraa.masroof.R.string.transaction_unknown_merchant
                else summary.messageFamily.toUiLabelRes(),
            smsBody = summary.body?.trim().orEmpty().ifEmpty { "—" },
            amountLabel = summary.amount?.let { MoneyUiFormatter.format(it, languageTag) },
            dateLabel = dateLabel,
            reasonLabel = review.reasons.firstOrNull().orEmpty(),
            dismissibleAsNonFinancial = dismissible,
            ignored = isIgnoredReview(review),
        )
    }

    private fun isIgnoredReview(review: com.baraa.masroof.domain.model.ReviewItem): Boolean =
        review.status == ReviewStatus.RESOLVED &&
            review.resolutionKind == ReviewResolutionKind.USER_NON_FINANCIAL

    private suspend fun toDetailUi(
        detail: ReviewDetailLoader.ReviewDetail,
        pairCandidates: List<ReviewListItemUi>,
    ): ReviewDetailUi {
        val review = detail.review
        val family = detail.messageFamily
        val isTransferOut = family == MessageFamily.TRANSFER_OUT
        val isTransferIn = family == MessageFamily.TRANSFER_IN
        val pendingMatch = review.kind == ReviewKind.PENDING_MATCH
        val dismissNonFinancial = shouldOfferNonFinancialDismiss(
            messageFamily = family,
            reasons = review.reasons,
        )
        val dateLabel = detail.receivedAt?.atZone(zoneId)?.toLocalDate()?.let(dateFormatter::format)
            ?: "—"
        val resolvedAtLabel = review.resolvedAt?.atZone(zoneId)?.toLocalDate()?.let(dateFormatter::format)
        val ignored = isIgnoredReview(review)
        val ownershipCard = if (!ignored && review.reasons.any { it in OWNERSHIP_CARD_REASONS }) {
            detail.cardRef?.takeIf { cardRef ->
                cardRef.last4 != null &&
                    reviewOwnershipWorkflow.isCardOwnershipUnknown(cardRef)
            }
        } else {
            null
        }
        return ReviewDetailUi(
            id = review.id,
            rawSmsId = review.rawSmsId,
            kind = review.kind,
            kindLabelRes = review.kind.toUiLabelRes(),
            sender = detail.sender,
            body = detail.body.orEmpty(),
            provenance = detail.provenance,
            bankId = detail.bankId,
            dateLabel = dateLabel,
            messageFamilyLabel = family?.name,
            messageFamily = family,
            amountLabel = detail.amount?.let { MoneyUiFormatter.format(it, languageTag) },
            merchant = detail.merchant,
            counterparty = detail.counterparty,
            reasonLabels = review.reasons,
            pairCandidates = pairCandidates,
            showExternalTransferAction = !ignored && (isTransferOut || isTransferIn) &&
                (pendingMatch || review.kind == ReviewKind.NEEDS_REVIEW),
            transferBetweenOwnAccounts = !ignored && (isTransferOut || isTransferIn) &&
                reviewWorkflowService.transferResolvesBetweenOwnAccounts(review.rawSmsId),
            showIncomingIncomeAction = !ignored && pendingMatch && isTransferIn,
            showFinancialTypeActions = !ignored && review.kind == ReviewKind.NEEDS_REVIEW &&
                !dismissNonFinancial &&
                family !in TRANSFER_MESSAGE_FAMILIES &&
                ownershipCard == null,
            showDismissNonFinancialAction = !ignored && review.kind == ReviewKind.NEEDS_REVIEW,
            ownershipCard = ownershipCard,
            showOwnershipActions = !ignored && ownershipCard != null,
            showRestoreActions = ignored,
            readOnly = false,
            resolvedAtLabel = resolvedAtLabel,
            bankChoices = bankChoicesFor(review, ignored),
        )
    }

    private fun bankChoicesFor(
        review: com.baraa.masroof.domain.model.ReviewItem,
        ignored: Boolean,
    ): List<ReviewBankChoiceUi> {
        if (ignored || review.kind != ReviewKind.NEEDS_REVIEW) return emptyList()
        if (!ExplicitBankSelection.offersBankChoice(review.reasons)) return emptyList()
        return selectableBanks.map { bank ->
            ReviewBankChoiceUi(
                bankId = bank.id,
                labelRes = if (bank == Bank.BANK_ALJAZIRA) com.baraa.masroof.R.string.bank_aljazira else null,
            )
        }
    }

    private companion object {
        val OWNERSHIP_CARD_REASONS = setOf(
            "purchase_instrument_ownership_unknown",
            "card_payment_ownership_unresolved",
        )
    }
}
