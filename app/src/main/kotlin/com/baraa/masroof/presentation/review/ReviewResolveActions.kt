package com.baraa.masroof.presentation.review

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.review.ExternalTransferDirection
import com.baraa.masroof.application.review.ReviewDetailLoader
import com.baraa.masroof.application.review.ReviewOwnershipWorkflow
import com.baraa.masroof.application.review.ReviewWorkflowResult
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.transaction.RestoreResult
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.ExplicitBankSelection
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.presentation.common.launchLogged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The decisions the user takes on a review, for [ReviewViewModel]. It shares the review state, so screens keep one state object. */
internal class ReviewResolveActions(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ReviewUiState>,
    private val loads: ReviewLoads,
    private val mapper: ReviewPresentationMapper,
    private val reviewWorkflowService: ReviewWorkflowService,
    private val detailLoader: ReviewDetailLoader,
    private val reviewOwnershipWorkflow: ReviewOwnershipWorkflow,
    private val transactionRestoreService: TransactionRestoreService,
    private val selectBankAndReparse: suspend (rawSmsId: String, bankId: String) -> Unit,
    private val appLogService: com.baraa.masroof.application.logging.AppLogService?,
) {
    fun dismissAllInformational() {
        scope.launchLogged(appLogService, AppLogCategories.REVIEW, "Review dismissAllInformational", onError = { state.update { it.copy(resolving = false, error = ReviewError.ACTION_FAILED) } }) {
            state.update { it.copy(resolving = true, error = null, message = null, actionErrorDetail = null) }
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
            loads.applySummaries(detailLoader.loadSummaries())
            state.update {
                it.copy(
                    resolving = false,
                    message = if (dismissed > 0) ReviewMessage.RESOLVED else it.message,
                    error = if (dismissed == 0 && dismissible.isNotEmpty()) ReviewError.ACTION_FAILED else null,
                )
            }
        }
    }

    fun resolveAsFinancialType(type: FinancialTransactionType) {
        val reviewId = state.value.selectedDetail?.id ?: return
        runAction {
            reviewWorkflowService.resolveAsFinancialType(reviewId, type)
        }
    }

    fun resolveAsExternalTransfer(reviewId: String, direction: ExternalTransferDirection) {
        if (state.value.selectedDetail?.id != reviewId) return
        runAction {
            reviewWorkflowService.resolveTransferAsExternal(reviewId, direction)
        }
    }

    fun resolveAsIgnored() {
        resolveAsNonFinancial()
    }

    fun selectBank(bankId: String) {
        val reviewId = state.value.selectedDetail?.id ?: return
        val rawSmsId = state.value.selectedDetail?.rawSmsId ?: return
        scope.launchLogged(appLogService, AppLogCategories.REVIEW, "Review selectBank", onError = {
            state.update { it.copy(resolving = false, error = ReviewError.ACTION_FAILED) }
        }) {
            state.update { it.copy(resolving = true, error = null, message = null, actionErrorDetail = null) }
            selectBankAndReparse(rawSmsId, bankId)
            val detail = detailLoader.loadDetail(reviewId)
            if (detail == null || detail.review.status == ReviewStatus.RESOLVED) {
                loads.refreshAfterAction(message = ReviewMessage.RESOLVED, closeDetail = true)
                return@launchLogged
            }
            val pairCandidates = loads.pairCandidatesFor(detail)
            val stillChoosing = ExplicitBankSelection.offersBankChoice(detail.review.reasons)
            state.update {
                it.copy(
                    loading = false,
                    resolving = false,
                    selectedDetail = mapper.toDetailUi(detail, pairCandidates),
                    message = if (stillChoosing) ReviewMessage.STILL_NEEDS_REVIEW else null,
                    error = null,
                )
            }
            loads.applySummaries(detailLoader.loadSummaries())
        }
    }

    fun resolveAsNonFinancial() {
        val reviewId = state.value.selectedDetail?.id ?: return
        runAction {
            reviewWorkflowService.resolveAsNonFinancial(reviewId)
        }
    }

    fun restoreIgnoredMessage(newType: FinancialTransactionType? = null) {
        val rawSmsId = state.value.selectedDetail?.rawSmsId ?: return
        scope.launchLogged(appLogService, AppLogCategories.REVIEW, "Review restoreIgnoredMessage", onError = {
            state.update {
                it.copy(resolving = false, error = ReviewError.ACTION_FAILED, actionErrorDetail = null)
            }
        }) {
            state.update { it.copy(resolving = true, error = null, message = null, actionErrorDetail = null) }
            // A failed type change still restored the message; the transaction shows the type it kept.
            val message = when (val result = transactionRestoreService.restore(rawSmsId, newType)) {
                is RestoreResult.Rejected -> {
                    state.update {
                        it.copy(resolving = false, error = ReviewError.ACTION_FAILED, actionErrorDetail = result.reason)
                    }
                    return@launchLogged
                }
                is RestoreResult.Success -> ReviewMessage.RESTORED
                // It posts later with the type the engine reads; the message says the type is changed from the transaction.
                RestoreResult.AwaitingReview -> ReviewMessage.RESTORED_PENDING
                is RestoreResult.TypeNotChanged -> ReviewMessage.RESTORED_TYPE_KEPT
            }
            val summaries = loads.loadCurrentSummaries()
            loads.applySummaries(summaries)
            state.update {
                it.copy(
                    loading = false,
                    resolving = false,
                    selectedDetail = null,
                    message = message,
                    error = null,
                )
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
        state.value.selectedDetail?.ownershipCard

    private fun runOwnershipAction(owned: Boolean, cardRef: CardReference) {
        scope.launchLogged(appLogService, AppLogCategories.REVIEW, "Review runOwnershipAction", onError = {
            state.update { it.copy(resolving = false, error = ReviewError.ACTION_FAILED) }
        }) {
            state.update { it.copy(resolving = true, error = null, message = null, actionErrorDetail = null) }
            if (owned) {
                reviewOwnershipWorkflow.confirmCardOwned(cardRef)
            } else {
                reviewOwnershipWorkflow.markCardExternal(cardRef)
            }
            reviewWorkflowService.reconcileOwnershipChange(
                ReviewWorkflowService.OwnershipChange.Card(cardRef),
            )
            val reviewId = state.value.selectedDetail?.id
            if (reviewId != null) {
                val detail = detailLoader.loadDetail(reviewId)
                if (detail == null) {
                    loads.refreshAfterAction(
                        message = ReviewMessage.RESOLVED,
                        closeDetail = true,
                    )
                    return@launchLogged
                }
                val pairCandidates = loads.pairCandidatesFor(detail)
                state.update {
                    it.copy(
                        loading = false,
                        resolving = false,
                        selectedDetail = mapper.toDetailUi(detail, pairCandidates),
                        message = ReviewMessage.RESOLVED,
                        error = null,
                    )
                }
                loads.applySummaries(detailLoader.loadSummaries())
            } else {
                loads.applySummaries(detailLoader.loadSummaries())
                state.update { it.copy(resolving = false, message = ReviewMessage.RESOLVED) }
            }
        }
    }

    fun resolveSelfTransferPair(partnerReviewId: String) {
        // Use the shown detail so the action locks the buttons at once; the service re-checks both families.
        val detail = state.value.selectedDetail ?: return
        val (outgoingId, incomingId) = when (detail.messageFamily) {
            MessageFamily.TRANSFER_OUT -> detail.id to partnerReviewId
            MessageFamily.TRANSFER_IN -> partnerReviewId to detail.id
            else -> return
        }
        runAction {
            reviewWorkflowService.resolveSelfTransferPair(outgoingId, incomingId)
        }
    }

    private fun runAction(action: suspend () -> ReviewWorkflowResult) {
        scope.launchLogged(appLogService, AppLogCategories.REVIEW, "Review runAction", onError = {
            state.update {
                it.copy(resolving = false, error = ReviewError.ACTION_FAILED, actionErrorDetail = null)
            }
        }) {
            state.update { it.copy(resolving = true, error = null, message = null, actionErrorDetail = null) }
            when (val result = action()) {
                is ReviewWorkflowResult.Success -> {
                    val resolved = result.review.status == ReviewStatus.RESOLVED
                    loads.refreshAfterAction(
                        message = if (resolved) ReviewMessage.RESOLVED else ReviewMessage.STILL_NEEDS_REVIEW,
                        closeDetail = resolved,
                    )
                }
                is ReviewWorkflowResult.Rejected -> {
                    if (result.reason == "transfer_evidence_changed") {
                        val detail = state.value.selectedDetail?.let { detailLoader.loadDetail(it.id) }
                        val candidates = detail?.let { loads.pairCandidatesFor(it) }.orEmpty()
                        val updated = detail?.let { mapper.toDetailUi(it, candidates) }
                        state.update { it.copy(selectedDetail = updated) }
                    }
                    state.update {
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
}
