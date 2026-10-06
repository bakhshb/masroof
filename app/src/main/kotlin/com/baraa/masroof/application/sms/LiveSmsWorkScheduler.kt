package com.baraa.masroof.application.sms

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules post-capture processing of durable RawSms evidence by id.
 *
 * Implementations must never place SMS body text in scheduled work.
 */
fun interface LiveSmsWorkScheduler {
    fun schedule(rawSmsId: String)
}

/**
 * [LiveSmsWorkScheduler] backed by unique one-time WorkManager work per rawSmsId.
 *
 * [ExistingWorkPolicy.KEEP] collapses duplicate schedules for the same evidence while it is
 * pending; a later reschedule after completion is safe because processing is idempotent.
 */
class WorkManagerLiveSmsWorkScheduler(
    private val workManager: () -> WorkManager,
) : LiveSmsWorkScheduler {
    override fun schedule(rawSmsId: String) {
        workManager().enqueueUniqueWork(
            uniqueWorkName(rawSmsId),
            ExistingWorkPolicy.KEEP,
            workRequest(rawSmsId),
        )
    }

    companion object {
        const val WORK_TAG = "live-sms-processing"
        const val BACKOFF_DELAY_SECONDS = 30L
        private const val UNIQUE_WORK_PREFIX = "live-sms:"

        fun uniqueWorkName(rawSmsId: String): String = "$UNIQUE_WORK_PREFIX$rawSmsId"

        fun workRequest(rawSmsId: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<LiveSmsProcessingWorker>()
                .setInputData(LiveSmsProcessingWorker.inputFor(rawSmsId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .build()
    }
}
