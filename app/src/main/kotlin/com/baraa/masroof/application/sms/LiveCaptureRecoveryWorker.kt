package com.baraa.masroof.application.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.data.room.DatabaseRestartRequiredException
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Schedules an inbox re-read after a live SMS could not be captured.
 *
 * Input is a time bound only, never SMS text.
 */
fun interface LiveCaptureRecoveryScheduler {
    fun schedule(receivedAfter: Instant)
}

/**
 * Re-reads the inbox from [KEY_RECEIVED_AFTER] through the normal historical scan, so a live SMS
 * whose capture failed is stored with the same dedupe and batch reconciliation as an import.
 */
class LiveCaptureRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
    private val scan: suspend (receivedAfter: Instant) -> SmsScanResult,
    private val startupReady: suspend () -> Boolean,
    private val appLogService: AppLogService? = null,
    private val onFinished: () -> Unit = {},
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val after = inputData.getLong(KEY_RECEIVED_AFTER, -1L).takeIf { it >= 0 } ?: return Result.failure()
        return try {
            if (!startupReady()) return Result.retry()
            val result = try {
                scan(Instant.ofEpochMilli(after))
            } finally {
                onFinished()
            }
            when (result.failure) {
                // A capture that failed again stored nothing; rows already stored come back as duplicates.
                null -> if (result.failed > 0) retryOrGiveUp("capture") else Result.success()
                is SmsScanFailure.DerivedIncomplete -> Result.success() // the batch keeps its own retry rows
                SmsScanFailure.PermissionDenied -> Result.failure()
                is SmsScanFailure.ProviderError -> retryOrGiveUp("provider")
            }
        } catch (_: DatabaseRestartRequiredException) {
            Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            retryOrGiveUp(e.javaClass.simpleName)
        }
    }

    private fun retryOrGiveUp(reason: String): Result {
        val exhausted = runAttemptCount + 1 >= MAX_ATTEMPTS
        appLogService?.warn(
            AppLogCategories.SMS,
            "LiveCaptureRecoveryWorker scan incomplete attempt=${runAttemptCount + 1} " +
                "retry_state=${if (exhausted) "exhausted" else "retry"} ($reason)",
        )
        return if (exhausted) Result.failure() else Result.retry()
    }

    class Factory(
        private val scanner: () -> HistoricalSmsScanner,
        private val startupReady: suspend () -> Boolean,
        private val appLogService: AppLogService? = null,
        private val onFinished: () -> Unit = {},
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == LiveCaptureRecoveryWorker::class.java.name) {
                LiveCaptureRecoveryWorker(
                    appContext,
                    workerParameters,
                    { scanner().scan(receivedAfter = it) },
                    startupReady,
                    appLogService,
                    onFinished,
                )
            } else {
                null
            }
    }

    companion object {
        const val KEY_RECEIVED_AFTER = "received_after_epoch_millis"
        const val MAX_ATTEMPTS = 5
        const val BACKOFF_DELAY_SECONDS = 30L

        /** The default SMS app may write its inbox row after the live broadcast. */
        const val INITIAL_DELAY_SECONDS = 60L
        private const val UNIQUE_WORK_PREFIX = "live-capture-recovery:"

        /**
         * One chain per minute of lost captures. A failure while a scan runs appends a fresh scan, so
         * an SMS that reached the inbox after the running scan's query is still read.
         */
        fun uniqueWorkName(receivedAfter: Instant): String = "$UNIQUE_WORK_PREFIX${receivedAfter.epochSecond / 60}"

        fun workRequest(receivedAfter: Instant): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<LiveCaptureRecoveryWorker>()
                .setInputData(Data.Builder().putLong(KEY_RECEIVED_AFTER, receivedAfter.toEpochMilli()).build())
                .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()

        fun enqueue(workManager: WorkManager, receivedAfter: Instant) {
            workManager.enqueueUniqueWork(uniqueWorkName(receivedAfter), ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest(receivedAfter))
        }
    }
}
