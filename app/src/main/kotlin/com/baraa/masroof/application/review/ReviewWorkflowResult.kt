package com.baraa.masroof.application.review

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.UserDecision

sealed interface ReviewWorkflowResult {
    data class Success(
        val review: ReviewItem,
        val transaction: FinancialTransaction? = null,
        val correction: UserDecision? = null,
        val pairedReview: ReviewItem? = null,
    ) : ReviewWorkflowResult

    data class Rejected(
        val reason: String,
    ) : ReviewWorkflowResult
}
