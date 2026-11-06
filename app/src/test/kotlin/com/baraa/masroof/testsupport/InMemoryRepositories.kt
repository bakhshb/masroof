package com.baraa.masroof.testsupport

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewStatus
import java.time.Instant

/** Read-only ledger over [all]; period reads keep the real newest-first order. [links] maps a row to its raw SMS ids. */
internal class FakeFinancialTransactionRepository(
    private val all: List<FinancialTransaction> = emptyList(),
    private val links: Map<String, List<String>> = emptyMap(),
) : EmptyFinancialTransactionRepository() {
    override suspend fun getById(id: String): FinancialTransaction? = all.firstOrNull { it.id == id }

    override suspend fun listAll(): List<FinancialTransaction> = all

    override suspend fun listOccurredBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<FinancialTransaction> =
        all.filter { !it.occurredAt.isBefore(startInclusive) && it.occurredAt.isBefore(endExclusive) }
            .sortedWith(compareByDescending<FinancialTransaction> { it.occurredAt }.thenByDescending { it.id })

    override suspend fun listRawSmsIds(transactionId: String) = links[transactionId].orEmpty()
}

/** Review queue with [requiredCount] open items; upserts return the item without storing it. */
internal class FakeReviewRepo(
    private val requiredCount: Int = 0,
) : EmptyReviewRepository() {
    override suspend fun listRequired(): List<ReviewItem> =
        (1..requiredCount).map { idx ->
            item("r$idx", "sms$idx", ReviewKind.NEEDS_REVIEW, listOf("x"), Instant.parse("2026-08-01T00:00:00Z"))
        }

    override suspend fun listAll(): List<ReviewItem> = listRequired()

    override suspend fun upsertRequired(
        rawSmsId: String,
        kind: ReviewKind,
        reasons: List<String>,
        now: Instant,
    ): ReviewItem = item("review:$rawSmsId", rawSmsId, kind, reasons, now)

    private fun item(id: String, rawSmsId: String, kind: ReviewKind, reasons: List<String>, at: Instant) =
        ReviewItem(
            id = id,
            rawSmsId = rawSmsId,
            kind = kind,
            status = ReviewStatus.REQUIRED,
            reasons = reasons,
            createdAt = at,
            updatedAt = at,
            resolvedAt = null,
            resolutionKind = null,
            resolvedTransactionId = null,
        )
}
