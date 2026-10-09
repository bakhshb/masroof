package com.baraa.masroof.application.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.transaction.ReconciliationIncompleteException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Schedules the single historical derived-recovery pass.
 *
 * Implementations must not enqueue one worker per RawSms id.
 */
fun interface HistoricalBatchRecoveryScheduler {
    fun schedule()
}

/**
 * Android execution adapter for [HistoricalDerivedRecovery].
 *
 * One unique work item reruns the batch derived pass. Failure leaves the retry rows
 * and asks WorkManager to run this same worker again.
 */
class HistoricalDerivedRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
    private val recovery: HistoricalDerivedRecovery,
    private val appLogService: AppLogService? = null,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        try {
            recovery.recoverPending()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logIncomplete(e)
            Result.retry()
        }

    private fun logIncomplete(error: Exception) {
        val incomplete = error as? ReconciliationIncompleteException
        val failures = incomplete?.let { " failures=${it.failureCount} id=${it.maskedRawSmsId}" }.orEmpty()
        val stage = incomplete?.stage ?: "reconciliation"
        appLogService?.warn(
            AppLogCategories.SMS,
            "HistoricalDerivedRecoveryWorker $stage incomplete$failures " +
                "attempt=${runAttemptCount + 1} retry_state=retained (${error.javaClass.simpleName})",
        )
    }

    class Factory(
        private val appLogService: AppLogService? = null,
        private val recovery: () -> HistoricalDerivedRecovery,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == HistoricalDerivedRecoveryWorker::class.java.name) {
                HistoricalDerivedRecoveryWorker(
                    appContext,
                    workerParameters,
                    recovery(),
                    appLogService,
                )
            } else {
                null
            }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "historical-derived-recovery"
        const val BACKOFF_DELAY_SECONDS = 30L

        fun workRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<HistoricalDerivedRecoveryWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()

        /** [ExistingWorkPolicy.KEEP]: one pending historical recovery at a time. */
        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, workRequest())
        }
    }
}
