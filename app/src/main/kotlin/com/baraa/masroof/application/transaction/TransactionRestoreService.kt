package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLog
import com.baraa.masroof.application.review.ReviewQueueUpdater
import kotlinx.coroutines.CancellationException
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.sms.time.InstantClock

sealed interface RestoreResult {
    data class Success(val transaction: FinancialTransaction) : RestoreResult

    data class Rejected(val reason: String) : RestoreResult

    /** Restored as financial but not posted yet: it posts once processing finishes, or its other leg or an ownership answer arrives. */
    data object AwaitingReview : RestoreResult

    /** Restored and posted, but the chosen type could not be applied; the user can change it on the transaction. */
    data class TypeNotChanged(val transaction: FinancialTransaction) : RestoreResult
}

/**
 * Restores a user-ignored SMS as a financial transaction with an optional type override.
 */
class TransactionRestoreService(
    private val reviewRepository: ReviewRepository,
    private val userDecisionRepository: UserDecisionRepository,
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val reconciliation: TransactionReconciliationService,
    private val reclassification: TransactionReclassificationService,
    private val clock: InstantClock,
    private val appLogService: AppLog,
    private val reviewQueueUpdater: ReviewQueueUpdater,
    private val processingRecovery: ProcessingRecovery,
) {
    suspend fun restore(
        rawSmsId: String,
        newType: FinancialTransactionType? = null,
    ): RestoreResult {
        val review = reviewRepository.findByRawSmsId(rawSmsId)
            ?: return RestoreResult.Rejected("review_not_found")
        if (review.status != ReviewStatus.RESOLVED ||
            review.resolutionKind != ReviewResolutionKind.USER_NON_FINANCIAL
        ) {
            return RestoreResult.Rejected("not_ignored")
        }

        markResolvedWithDecision(review.id, rawSmsId, ReviewResolutionKind.USER_FINANCIAL_TYPE, UserDecisionKind.RESTORE)
            ?: return RestoreResult.Rejected("review_clear_failed")

        val report = try {
            reconciliation.reconcileAffectedRawSmsIds(listOf(rawSmsId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // An incomplete pass keeps the financial decision and the RawSms durably retryable; it never rolls back.
        val completeReport = report?.takeIf(ReconciliationCompletionPolicy::isComplete)
        if (completeReport == null) retainRetry(rawSmsId, report?.summary?.failed ?: 1, ReconciliationCompletionPolicy.STAGE_RECONCILIATION)
        val tx = financialTransactionRepository.findByRawSmsId(rawSmsId)
        if (tx == null) {
            // Retrying, pending match and needs-review are not failures: a later pass posts it.
            if (completeReport == null || completeReport.reviewCandidates.any { it.rawSmsId == rawSmsId }) return RestoreResult.AwaitingReview
            val rollbackReason = rollbackToIgnored(review.id, rawSmsId)
            return RestoreResult.Rejected(rollbackReason ?: "reconcile_failed")
        }

        try {
            completeReport?.let { reviewQueueUpdater.applyReport(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The row is posted; only the queue refresh retries. The type change below saves its own user decision.
            retainRetry(rawSmsId, failureCount = 1, stage = ReconciliationCompletionPolicy.STAGE_REVIEW_UPDATE)
        }
        if (newType == null || newType == tx.type) {
            logRestore(rawSmsId, tx.id, newType)
            return RestoreResult.Success(tx)
        }

        return when (val result = reclassification.reclassify(tx.id, newType)) {
            is ReclassificationResult.Success -> {
                logRestore(rawSmsId, result.transaction.id, newType)
                RestoreResult.Success(result.transaction)
            }
            // The restore itself succeeded: keep the posted row and the financial decision; only the type change failed.
            is ReclassificationResult.Rejected -> {
                logRestore(rawSmsId, tx.id, null)
                RestoreResult.TypeNotChanged(tx)
            }
        }
    }

    private suspend fun retainRetry(rawSmsId: String, failureCount: Int, stage: String) {
        appLogService.error(
            AppLogCategories.TRANSACTION,
            "Derived $stage incomplete failures=$failureCount " +
                "id=${AppLogFormatting.maskId(rawSmsId)} retry_state=historical_batch",
        )
        processingRecovery.retryLater(listOf(rawSmsId))
    }

    /** Returns a failure reason when rollback could not complete; null on success. */
    private suspend fun rollbackToIgnored(reviewId: String, rawSmsId: String): String? {
        // No link means nothing was posted; the production delete reports false for that case.
        if (financialTransactionRepository.isRawSmsLinked(rawSmsId) &&
            !financialTransactionRepository.deleteIfExclusiveRawSmsLink(rawSmsId)
        ) {
            return "rollback_delete_failed"
        }
        markResolvedWithDecision(reviewId, rawSmsId, ReviewResolutionKind.USER_NON_FINANCIAL, UserDecisionKind.RESTORE_REVERTED)
            ?: return "rollback_review_failed"
        return null
    }

    /** The review resolution and its `user_decision` row commit together. */
    private suspend fun markResolvedWithDecision(
        reviewId: String,
        rawSmsId: String,
        resolutionKind: ReviewResolutionKind,
        decisionKind: UserDecisionKind,
    ): ReviewItem? {
        val now = clock.now()
        return userDecisionRepository.recordWith(UserDecision(rawSmsId, decisionKind, now), { reviewRepository.getById(reviewId) }) {
            reviewRepository.markResolved(id = reviewId, resolutionKind = resolutionKind, resolvedAt = now, resolvedTransactionId = null)
        }
    }

    private fun logRestore(
        rawSmsId: String,
        transactionId: String,
        newType: FinancialTransactionType?,
    ) {
        val typeSuffix = newType?.name?.lowercase()?.let { " as $it" } ?: ""
        appLogService.info(
            AppLogCategories.TRANSACTION,
            "Restored SMS ${AppLogFormatting.maskId(rawSmsId)} to transaction ${AppLogFormatting.maskId(transactionId)}$typeSuffix",
        )
    }
}
