package com.baraa.masroof.application.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import kotlinx.coroutines.CancellationException

/**
 * Android execution adapter for post-capture processing of one durable RawSms.
 *
 * Input is the rawSmsId only. Delegates to [ProcessStoredSmsUseCase]; contains no bank
 * parsing or financial rules. Live reconciliation is the bounded affected-id pass.
 * Retrying is safe because stored-SMS processing is idempotent.
 * Ownership, reconciliation, and review-refresh failures are retried, including a
 * reconciliation report whose failed count is nonzero. A direct review write that
 * fails stays retryable past [MAX_ATTEMPTS] until a review row or a LIVE
 * processing-retry marker exists. Exchange-rate enrichment failure is not.
 */
class LiveSmsProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
    private val processStoredSms: ProcessStoredSmsUseCase,
    private val appLogService: AppLogService? = null,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val rawSmsId = inputData.getString(KEY_RAW_SMS_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.failure()

        val outcome = try {
            processStoredSms.process(rawSmsId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logUnexpectedFailure(stage = "process", e)
            return retryOrGiveUp()
        }

        return when (outcome) {
            is SmsIngestionResult.DerivedIncomplete -> {
                logDerivedIncomplete(outcome)
                retryDerivedOrGiveUp(outcome.rawSmsId)
            }
            is SmsIngestionResult.Failed -> when (outcome.message) {
                ProcessStoredSmsUseCase.REASON_RAW_SMS_NOT_FOUND -> Result.failure()
                ProcessStoredSmsUseCase.REASON_REVIEW_NOT_PERSISTED -> Result.retry()
                else -> retryOrGiveUp()
            }
            else -> Result.success()
        }
    }

    /**
     * Intermediate derived failures stay retryable without a recovery marker.
     * The final attempt may stop only after the recovery marker has been saved.
     */
    private suspend fun retryDerivedOrGiveUp(rawSmsId: String): Result {
        if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
        return try {
            processStoredSms.recordExhaustedDerivedProcessing(rawSmsId)
            Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logUnexpectedFailure(stage = "record_exhausted_derived", e)
            Result.retry()
        }
    }

    private fun logDerivedIncomplete(outcome: SmsIngestionResult.DerivedIncomplete) {
        val failures = outcome.failureCount?.let { " failures=$it" }.orEmpty()
        val retryState = if (runAttemptCount + 1 < MAX_ATTEMPTS) "retry" else "exhausted"
        appLogService?.warn(
            AppLogCategories.SMS,
            "LiveSmsProcessingWorker derived ${outcome.stage.name.lowercase()} incomplete$failures " +
                "id=${AppLogFormatting.maskId(outcome.rawSmsId)} " +
                "attempt=${runAttemptCount + 1} retry_state=$retryState " +
                "(${outcome.cause?.javaClass?.simpleName ?: "none"})",
        )
    }

    private fun logUnexpectedFailure(stage: String, error: Exception) {
        appLogService?.warn(
            AppLogCategories.SMS,
            "LiveSmsProcessingWorker $stage failed on attempt ${runAttemptCount + 1} (${error::class.java.simpleName})",
        )
    }

    /**
     * After [MAX_ATTEMPTS], a parse failure whose `processing_error` review was saved
     * may stop. [ProcessStoredSmsUseCase.REASON_REVIEW_NOT_PERSISTED] does not: that
     * attempt has no review row and no LIVE retry marker yet.
     */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()

    /**
     * Creates [LiveSmsProcessingWorker]; returns null for any other worker so WorkManager
     * falls back to its default reflective factory.
     */
    class Factory(
        private val processStoredSms: () -> ProcessStoredSmsUseCase,
        private val appLogService: AppLogService? = null,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == LiveSmsProcessingWorker::class.java.name) {
                LiveSmsProcessingWorker(appContext, workerParameters, processStoredSms(), appLogService)
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
