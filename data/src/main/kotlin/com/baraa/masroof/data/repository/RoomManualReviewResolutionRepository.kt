package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import androidx.room.withTransaction
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.mapper.ReviewItemMapper
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.ManualReviewResolutionRepository
import com.baraa.masroof.domain.repository.ManualReviewResolutionResult
import java.time.Instant

/**
 * Room-backed atomic FT + review resolution. Uses [MasroofDatabase.withTransaction]
 * so FinancialTransaction persistence, ReviewItem updates and the `user_decision` row commit together.
 */
class RoomManualReviewResolutionRepository(
    private val database: MasroofDatabase,
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : ManualReviewResolutionRepository {
    private val reviewDao = database.reviewItemDao()

    override suspend fun persistSingleResolution(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        reviewId: String,
        resolutionKind: ReviewResolutionKind,
        resolvedAt: Instant,
        validateBeforeWrite: suspend () -> Boolean,
    ): ManualReviewResolutionResult =
        persistResolution(transaction, rawSmsIds, listOf(reviewId), resolutionKind, resolvedAt, validateBeforeWrite)

    override suspend fun persistPairResolution(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        firstReviewId: String,
        secondReviewId: String,
        resolutionKind: ReviewResolutionKind,
        resolvedAt: Instant,
    ): ManualReviewResolutionResult =
        persistResolution(transaction, rawSmsIds, listOf(firstReviewId, secondReviewId), resolutionKind, resolvedAt)

    /** Saves the transaction, then resolves [reviewIds] in order; any review failure rolls back all. */
    private suspend fun persistResolution(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        reviewIds: List<String>,
        resolutionKind: ReviewResolutionKind,
        resolvedAt: Instant,
        validateBeforeWrite: (suspend () -> Boolean)? = null,
    ): ManualReviewResolutionResult = accessGate.withAccess {
        val decisionKind = decisionKind(resolutionKind)
        try {
            database.withTransaction {
                if (validateBeforeWrite != null && !validateBeforeWrite()) {
                    throw ReviewResolutionRollback("transfer_evidence_changed")
                }
                when (
                    val save = financialTransactionRepository.save(transaction, rawSmsIds)
                ) {
                    is FinancialTransactionSaveResult.Conflict ->
                        ManualReviewResolutionResult.Conflict(
                            reason = "link_conflict",
                            rawSmsId = save.rawSmsId,
                            existingTransactionId = save.existingTransactionId,
                        )

                    FinancialTransactionSaveResult.Saved,
                    FinancialTransactionSaveResult.AlreadyExists,
                    -> {
                        // A repeated submit finds its own row and every review already resolved to it by the same
                        // user choice: no new decision. An automatic resolution to the same id is not a repeat.
                        val repeat = save == FinancialTransactionSaveResult.AlreadyExists && reviewIds.all { id ->
                            reviewDao.getById(id)?.let {
                                it.status == ReviewStatus.RESOLVED.name && it.resolvedTransactionId == transaction.id &&
                                    it.resolutionKind == resolutionKind.name
                            } == true
                        }
                        val reviews = reviewIds.map { reviewId ->
                            resolveRequiredOrRollback(
                                reviewId = reviewId,
                                resolutionKind = resolutionKind,
                                resolvedAt = resolvedAt,
                                transactionId = transaction.id,
                            )
                        }
                        if (!repeat) database.appendDecision(
                            UserDecision.posting(
                                kind = decisionKind,
                                rawSmsId = reviews.first().rawSmsId,
                                transaction = transaction,
                                decidedAt = resolvedAt,
                                pairedRawSmsId = reviews.getOrNull(1)?.rawSmsId,
                            ),
                        )
                        ManualReviewResolutionResult.Success(transaction = transaction, reviews = reviews)
                    }
                }
            }
        } catch (e: ReviewResolutionRollback) {
            ManualReviewResolutionResult.Failed(e.reason)
        }
    }

    private suspend fun resolveRequiredOrRollback(
        reviewId: String,
        resolutionKind: ReviewResolutionKind,
        resolvedAt: Instant,
        transactionId: String,
    ): ReviewItem {
        val existing = reviewDao.getById(reviewId)
            ?: throw ReviewResolutionRollback("review_not_found")

        if (existing.status == ReviewStatus.RESOLVED.name) {
            if (existing.resolvedTransactionId == transactionId) {
                return ReviewItemMapper.toDomain(existing)
            }
            throw ReviewResolutionRollback("review_already_resolved_elsewhere")
        }

        val updated = reviewDao.resolveIfRequired(
            id = reviewId,
            status = ReviewStatus.RESOLVED.name,
            resolutionKind = resolutionKind.name,
            resolvedAtEpochMillis = resolvedAt.toEpochMilli(),
            resolvedTransactionId = transactionId,
            updatedAtEpochMillis = resolvedAt.toEpochMilli(),
        )
        if (updated != 1) {
            throw ReviewResolutionRollback("review_resolution_failed")
        }
        val after = reviewDao.getById(reviewId)
            ?: throw ReviewResolutionRollback("review_missing_after_resolve")
        if (after.status != ReviewStatus.RESOLVED.name ||
            after.resolutionKind != resolutionKind.name ||
            after.resolvedTransactionId != transactionId
        ) {
            throw ReviewResolutionRollback("review_resolution_verify_failed")
        }
        return ReviewItemMapper.toDomain(after)
    }

    private fun decisionKind(resolutionKind: ReviewResolutionKind): UserDecisionKind = when (resolutionKind) {
        ReviewResolutionKind.USER_FINANCIAL_TYPE -> UserDecisionKind.FINANCIAL_TYPE
        ReviewResolutionKind.USER_EXTERNAL_TRANSFER -> UserDecisionKind.EXTERNAL_TRANSFER
        ReviewResolutionKind.USER_SELF_TRANSFER_PAIR -> UserDecisionKind.SELF_TRANSFER_PAIR
        else -> error("$resolutionKind is not a posting decision")
    }

    private class ReviewResolutionRollback(val reason: String) : Exception(reason)
}
