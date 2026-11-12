package com.baraa.masroof.domain.model

/**
 * High-level reason a RawSms remains unresolved after reconciliation.
 * Fine-grained reason codes are kept beside the review.
 */
enum class ReviewKind {
    NEEDS_REVIEW,
    PENDING_MATCH,
}
