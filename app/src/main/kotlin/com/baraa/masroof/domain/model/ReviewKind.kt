package com.baraa.masroof.domain.model

/**
 * High-level reason a RawSms remains unresolved after reconciliation.
 * Fine-grained codes live in [ReviewItem.reasons].
 */
enum class ReviewKind {
    NEEDS_REVIEW,
    PENDING_MATCH,
}
