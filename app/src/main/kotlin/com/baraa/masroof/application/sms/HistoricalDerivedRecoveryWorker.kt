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
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        try {
            recovery.recoverPending()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }

    class Factory(
        private val recovery: () -> HistoricalDerivedRecovery,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == HistoricalDerivedRecoveryWorker::class.java.name) {
                HistoricalDerivedRecoveryWorker(appContext, workerParameters, recovery())
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
