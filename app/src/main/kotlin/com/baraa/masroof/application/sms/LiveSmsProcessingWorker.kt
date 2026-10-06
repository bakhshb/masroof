package com.baraa.masroof.application.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import kotlinx.coroutines.CancellationException

/**
 * Android execution adapter for post-capture processing of one durable RawSms.
 *
 * Input is the rawSmsId only. Delegates to [ProcessStoredSmsUseCase]; contains no bank
 * parsing or financial rules. Retrying is safe because stored-SMS processing is idempotent.
 */
class LiveSmsProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
    private val processStoredSms: ProcessStoredSmsUseCase,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val rawSmsId = inputData.getString(KEY_RAW_SMS_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.failure()

        val outcome = try {
            processStoredSms.process(rawSmsId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return retryOrGiveUp()
        }

        return when {
            outcome !is SmsIngestionResult.Failed -> Result.success()
            outcome.message == ProcessStoredSmsUseCase.REASON_RAW_SMS_NOT_FOUND -> Result.failure()
            else -> retryOrGiveUp()
        }
    }

    /** After [MAX_ATTEMPTS] the evidence keeps its processing_error review for reparse. */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()

    /**
     * Creates [LiveSmsProcessingWorker]; returns null for any other worker so WorkManager
     * falls back to its default reflective factory.
     */
    class Factory(
        private val processStoredSms: () -> ProcessStoredSmsUseCase,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == LiveSmsProcessingWorker::class.java.name) {
                LiveSmsProcessingWorker(appContext, workerParameters, processStoredSms())
            } else {
                null
            }
    }

    companion object {
        const val KEY_RAW_SMS_ID = "raw_sms_id"
        const val MAX_ATTEMPTS = 5

        fun inputFor(rawSmsId: String): Data =
            Data.Builder().putString(KEY_RAW_SMS_ID, rawSmsId).build()
    }
}
