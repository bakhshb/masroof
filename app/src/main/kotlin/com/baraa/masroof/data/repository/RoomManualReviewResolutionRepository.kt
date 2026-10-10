package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import androidx.room.withTransaction
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.mapper.ReviewItemMapper
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.ManualReviewResolutionRepository
import com.baraa.masroof.domain.repository.ManualReviewResolutionResult
import java.time.Instant

/**
 * Room-backed atomic FT + review resolution. Uses [MasroofDatabase.withTransaction]
 * so FinancialTransaction persistence and ReviewItem updates commit together.
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
    ): ManualReviewResolutionResult = accessGate.withAccess {
        try {
            database.withTransaction {
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
                        val review = resolveRequiredOrRollback(
                            reviewId = reviewId,
                            resolutionKind = resolutionKind,
                            resolvedAt = resolvedAt,
                            transactionId = transaction.id,
                        )
                        ManualReviewResolutionResult.Success(
                            transaction = transaction,
                            reviews = listOf(review),
                        )
                    }
                }
            }
        } catch (e: ReviewResolutionRollback) {
            ManualReviewResolutionResult.Failed(e.reason)
        }
    }

    override suspend fun persistPairResolution(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        firstReviewId: String,
        secondReviewId: String,
        resolutionKind: ReviewResolutionKind,
        resolvedAt: Instant,
    ): ManualReviewResolutionResult = accessGate.withAccess {
        try {
            database.withTransaction {
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
                        val first = resolveRequiredOrRollback(
                            reviewId = firstReviewId,
                            resolutionKind = resolutionKind,
                            resolvedAt = resolvedAt,
                            transactionId = transaction.id,
                        )
                        val second = resolveRequiredOrRollback(
                            reviewId = secondReviewId,
                            resolutionKind = resolutionKind,
                            resolvedAt = resolvedAt,
                            transactionId = transaction.id,
                        )
                        ManualReviewResolutionResult.Success(
                            transaction = transaction,
                            reviews = listOf(first, second),
                        )
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

    private class ReviewResolutionRollback(val reason: String) : Exception(reason)
}
