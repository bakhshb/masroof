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
    private val requeue: (receivedAfter: Instant, requeues: Int) -> Unit = { _, _ -> },
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val after = inputData.getLong(KEY_RECEIVED_AFTER, -1L).takeIf { it >= 0 } ?: return Result.failure()
        return try {
            // A fresh request: waiting for startup must not use up this request's attempts.
            if (!startupReady()) return requeueOrGiveUp(after, "startup")
            val result = try {
                scan(Instant.ofEpochMilli(after))
            } finally {
                onFinished()
            }
            when (result.failure) {
                // A capture that failed again stored nothing; rows already stored come back as duplicates.
                null -> if (result.failed > 0) retryOrRequeue(after, "capture") else Result.success()
                // The batch keeps retry rows for what it stored; a capture that failed again has none.
                is SmsScanFailure.DerivedIncomplete -> if (result.failed > 0) retryOrRequeue(after, "capture") else Result.success()
                SmsScanFailure.PermissionDenied -> giveUp("permission")
                is SmsScanFailure.ProviderError -> retryOrRequeue(after, "provider")
            }
        } catch (_: DatabaseRestartRequiredException) {
            Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            retryOrRequeue(after, e.javaClass.simpleName)
        }
    }

    /** Nothing durable remembers a lost capture but this work, so exhausted attempts hand over to a later request. */
    private fun retryOrRequeue(after: Long, reason: String): Result {
        val exhausted = runAttemptCount + 1 >= MAX_ATTEMPTS
        appLogService?.warn(
            AppLogCategories.SMS,
            "LiveCaptureRecoveryWorker scan incomplete attempt=${runAttemptCount + 1} " +
                "retry_state=${if (exhausted) "exhausted" else "retry"} ($reason)",
        )
        return if (exhausted) requeueOrGiveUp(after, reason) else Result.retry()
    }

    private fun requeueOrGiveUp(after: Long, reason: String): Result {
        val requeues = inputData.getInt(KEY_REQUEUES, 0) + 1
        if (requeues > MAX_REQUEUES) return giveUp(reason)
        try {
            requeue(Instant.ofEpochMilli(after), requeues)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return giveUp("requeue ${e.javaClass.simpleName}")
        }
        return Result.success()
    }

    // Success, not failure: a scan appended behind this one in the chain must still run.
    private fun giveUp(reason: String): Result {
        appLogService?.warn(AppLogCategories.SMS, "LiveCaptureRecoveryWorker gave up ($reason)")
        return Result.success()
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
                ) { after, waits -> enqueue(WorkManager.getInstance(appContext), after, requeueDelaySeconds(waits), waits) }
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

        const val KEY_REQUEUES = "requeues"
        private const val REQUEUE_FIRST_SECONDS = 15 * 60L
        private const val REQUEUE_MAX_SECONDS = 6 * 60 * 60L

        /** About a week of later requests (15 min doubling to 6 h); after that only a manual import recovers it. */
        const val MAX_REQUEUES = 33

        /** Delay of a later request: 15 min, doubling, at most 6 h. */
        fun requeueDelaySeconds(waits: Int): Long =
            (REQUEUE_FIRST_SECONDS shl (waits - 1).coerceIn(0, 5)).coerceAtMost(REQUEUE_MAX_SECONDS)
        private const val UNIQUE_WORK_PREFIX = "live-capture-recovery:"

        /**
         * One chain per minute of lost captures. A failure while a scan runs appends a fresh scan, so
         * an SMS that reached the inbox after the running scan's query is still read.
         */
        fun uniqueWorkName(receivedAfter: Instant): String = "$UNIQUE_WORK_PREFIX${receivedAfter.epochSecond / 60}"

        fun workRequest(receivedAfter: Instant, delaySeconds: Long = INITIAL_DELAY_SECONDS, requeues: Int = 0): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<LiveCaptureRecoveryWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_RECEIVED_AFTER, receivedAfter.toEpochMilli())
                        .putInt(KEY_REQUEUES, requeues)
                        .build(),
                )
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()

        fun enqueue(
            workManager: WorkManager,
            receivedAfter: Instant,
            delaySeconds: Long = INITIAL_DELAY_SECONDS,
            requeues: Int = 0,
        ) {
            workManager.enqueueUniqueWork(
                uniqueWorkName(receivedAfter),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest(receivedAfter, delaySeconds, requeues),
            )
        }
    }
}
