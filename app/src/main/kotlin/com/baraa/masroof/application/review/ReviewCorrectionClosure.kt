package com.baraa.masroof.application.review

import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus

/**
 * The one decision fact processing still reads from `review_item`: a correction whose own pass posted the SMS
 * closes its review as `USER_CORRECTION`, and that closure protects the posted row like an outcome decision.
 * `user_decision` holds the correction but not whether that pass posted, so a correction posted only by a later
 * pass (its review `AUTO_NO_LONGER_REQUIRED`) is not protected. Reading the store alone would protect it too and
 * change what reconciliation keeps; that difference is for the plan 3.2 shadow comparison (docs/ARCHITECTURE.md,
 * "Reading decisions").
 */
object ReviewCorrectionClosure {
    fun isClosed(review: ReviewItem?): Boolean =
        review?.status == ReviewStatus.RESOLVED && review.resolutionKind == ReviewResolutionKind.USER_CORRECTION

    /** A human decision protects this SMS from automatic rewrites: any outcome, or this closure. */
    fun protects(decisions: EffectiveUserDecisions, review: ReviewItem?): Boolean =
        decisions.outcome != null || isClosed(review)
}
