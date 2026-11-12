package com.baraa.masroof.domain.model

import java.time.Instant

/**
 * Durable review queue row for unresolved RawSms evidence.
 *
 * Identity is keyed by [rawSmsId] (`review:<rawSmsId>`), not replaceable ParsedEvent ids.
 */
data class ReviewItem(
    val id: String,
    val rawSmsId: String,
    val kind: ReviewKind,
    val status: ReviewStatus,
    val reasons: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val resolvedAt: Instant?,
    val resolutionKind: ReviewResolutionKind?,
    val resolvedTransactionId: String?,
)
