package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import kotlinx.coroutines.CancellationException
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.ReviewRepository
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
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val reconciliation: TransactionReconciliationService,
    private val reclassification: TransactionReclassificationService,
    private val clock: InstantClock,
    private val appLogService: AppLogService? = null,
    private val reviewQueueUpdater: ReviewQueueUpdater? = null,
    private val processingRecovery: ProcessingRecovery? = null,
    private val onHistoricalRetry: (() -> Unit)? = null,
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

        reviewRepository.markResolved(
            id = review.id,
            resolutionKind = ReviewResolutionKind.USER_FINANCIAL_TYPE,
            resolvedAt = clock.now(),
            resolvedTransactionId = null,
        ) ?: return RestoreResult.Rejected("review_clear_failed")

        val report = try {
            reconciliation.reconcileAffectedRawSmsIds(listOf(rawSmsId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return incompleteRestore(rawSmsId, failureCount = 1)
        }
        if (!ReconciliationCompletionPolicy.isComplete(report)) {
            return incompleteRestore(rawSmsId, report.summary.failed)
        }
        val tx = financialTransactionRepository.findByRawSmsId(rawSmsId)
        if (tx == null) {
            // Pending match and needs-review are not failures: keep the financial decision so a later pass posts it.
            if (report.reviewCandidates.any { it.rawSmsId == rawSmsId }) return RestoreResult.AwaitingReview
            val rollbackReason = rollbackToIgnored(review.id, rawSmsId)
            return RestoreResult.Rejected(rollbackReason ?: "reconcile_failed")
        }

        try {
            reviewQueueUpdater?.applyReport(report)
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

    /**
     * The caller already recorded [ReviewResolutionKind.USER_FINANCIAL_TYPE].
     * A failed reconcile keeps that decision and the RawSms durably retryable.
     * It does not write [ReviewResolutionKind.USER_NON_FINANCIAL] back.
     */
    private suspend fun incompleteRestore(
        rawSmsId: String,
        failureCount: Int,
    ): RestoreResult {
        retainRetry(rawSmsId, failureCount, ReconciliationCompletionPolicy.STAGE_RECONCILIATION)
        return RestoreResult.AwaitingReview
    }

    private suspend fun retainRetry(rawSmsId: String, failureCount: Int, stage: String) {
        logIncomplete(rawSmsId, failureCount, retryState = "historical_batch", stage = stage)
        processingRecovery?.markForHistoricalRetry(listOf(rawSmsId), onHistoricalRetry)
    }

    private fun logIncomplete(
        rawSmsId: String,
        failureCount: Int,
        retryState: String,
        stage: String = ReconciliationCompletionPolicy.STAGE_RECONCILIATION,
    ) {
        appLogService?.error(
            AppLogCategories.TRANSACTION,
            "Derived $stage incomplete failures=$failureCount " +
                "id=${AppLogFormatting.maskId(rawSmsId)} retry_state=$retryState",
        )
    }

    /** Returns a failure reason when rollback could not complete; null on success. */
    private suspend fun rollbackToIgnored(reviewId: String, rawSmsId: String): String? {
        // No link means nothing was posted; the production delete reports false for that case.
        if (financialTransactionRepository.isRawSmsLinked(rawSmsId) &&
            !financialTransactionRepository.deleteIfExclusiveRawSmsLink(rawSmsId)
        ) {
            return "rollback_delete_failed"
        }
        reviewRepository.markResolved(
            id = reviewId,
            resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL,
            resolvedAt = clock.now(),
            resolvedTransactionId = null,
        ) ?: return "rollback_review_failed"
        return null
    }

    private fun logRestore(
        rawSmsId: String,
        transactionId: String,
        newType: FinancialTransactionType?,
    ) {
        val typeSuffix = newType?.name?.lowercase()?.let { " as $it" } ?: ""
        appLogService?.info(
            AppLogCategories.TRANSACTION,
            "Restored SMS ${AppLogFormatting.maskId(rawSmsId)} to transaction ${AppLogFormatting.maskId(transactionId)}$typeSuffix",
        )
    }
}
