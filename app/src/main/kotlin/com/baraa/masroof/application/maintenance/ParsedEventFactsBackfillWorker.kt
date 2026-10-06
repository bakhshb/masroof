package com.baraa.masroof.application.maintenance

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
 * Android execution adapter for background-safe schema facts backfill.
 *
 * Delegates to [ParsedEventFactsBackfillCoordinator.runIfNeeded]; re-parsing is idempotent,
 * so retries are safe. An incomplete run is retried with backoff up to [MAX_ATTEMPTS]; after
 * that the schema version stays unrecorded and the next launch schedules it again.
 */
class ParsedEventFactsBackfillWorker(
    appContext: Context,
    params: WorkerParameters,
    private val coordinator: ParsedEventFactsBackfillCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val outcome = try {
            coordinator.runIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return retryOrGiveUp()
        }
        return when (outcome) {
            BackfillOutcome.UP_TO_DATE, BackfillOutcome.COMPLETED -> Result.success()
            BackfillOutcome.INCOMPLETE -> retryOrGiveUp()
        }
    }

    private fun retryOrGiveUp(): Result =
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()

    /**
     * Creates [ParsedEventFactsBackfillWorker]; returns null for any other worker so other
     * factories (or WorkManager's default) handle it.
     */
    class Factory(
        private val coordinator: () -> ParsedEventFactsBackfillCoordinator,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == ParsedEventFactsBackfillWorker::class.java.name) {
                ParsedEventFactsBackfillWorker(appContext, workerParameters, coordinator())
            } else {
                null
            }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "parsed-event-facts-backfill"
        const val MAX_ATTEMPTS = 5
        const val BACKOFF_DELAY_SECONDS = 60L

        fun workRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ParsedEventFactsBackfillWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()

        /** [ExistingWorkPolicy.KEEP]: one pending backfill at a time. */
        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, workRequest())
        }
    }
}
