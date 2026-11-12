package com.baraa.masroof.presentation.review

import com.baraa.masroof.application.review.ReviewDetailLoader
import com.baraa.masroof.domain.model.ReviewKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The list reads [ReviewViewModel] and its actions share, applied to the one review state. */
internal class ReviewLoads(
    private val state: MutableStateFlow<ReviewUiState>,
    private val detailLoader: ReviewDetailLoader,
    private val mapper: ReviewPresentationMapper,
) {
    suspend fun refreshAfterAction(
        message: ReviewMessage,
        closeDetail: Boolean,
    ) {
        val summaries = loadCurrentSummaries()
        val items = summaries.map(mapper::toListItem)
        state.update {
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

    suspend fun loadCurrentSummaries(): List<ReviewDetailLoader.ReviewSummary> =
        when (state.value.listMode) {
            ReviewListMode.PENDING -> detailLoader.loadSummaries()
            ReviewListMode.IGNORED -> detailLoader.loadIgnoredSummaries()
        }

    suspend fun pairCandidatesFor(detail: ReviewDetailLoader.ReviewDetail): List<ReviewListItemUi> =
        if (detail.review.kind == ReviewKind.PENDING_MATCH) {
            detailLoader.loadPairCandidates(detail.review.id).map(mapper::toListItem)
        } else {
            emptyList()
        }

    fun applySummaries(summaries: List<ReviewDetailLoader.ReviewSummary>) {
        val items = summaries.map(mapper::toListItem)
        state.update {
            it.copy(
                loading = false,
                items = items,
                informationalDismissCount = items.count { item -> item.dismissibleAsNonFinancial },
                error = null,
            )
        }
    }
}
