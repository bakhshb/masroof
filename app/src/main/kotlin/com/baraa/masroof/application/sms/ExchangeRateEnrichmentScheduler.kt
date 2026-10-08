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

/** Application-owned enrichment pass invoked by the Android worker adapter. */
fun interface PendingExchangeRateEnricher {
    suspend fun enrichPending()
}

/**
 * Schedules a coalesced pass of pending exchange-rate enrichment after live SMS processing.
 */
fun interface ExchangeRateEnrichmentScheduler {
    fun schedule()
}

/**
 * Runs enrichment inline. Used by unit tests and other JVM callers without WorkManager.
 */
class ImmediateExchangeRateEnrichmentScheduler(
    private val enricher: PendingExchangeRateEnricher,
) : ExchangeRateEnrichmentScheduler {
    override fun schedule() {
        try {
            kotlinx.coroutines.runBlocking { enricher.enrichPending() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort, same as the live processing boundary.
        }
    }
}

/**
 * [ExchangeRateEnrichmentScheduler] backed by one unique WorkManager job for all pending rows.
 *
 * [ExistingWorkPolicy.KEEP] collapses bursts of live SMS into a single enrichment run.
 */
class WorkManagerExchangeRateEnrichmentScheduler(
    private val workManager: () -> WorkManager,
) : ExchangeRateEnrichmentScheduler {
    override fun schedule() {
        ExchangeRateEnrichmentWorker.enqueue(workManager())
    }

    companion object {
        const val UNIQUE_WORK_NAME = "exchange-rate-enrichment"
        const val WORK_TAG = "exchange-rate-enrichment"
        const val BACKOFF_DELAY_SECONDS = 30L

        fun workRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ExchangeRateEnrichmentWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .build()
    }
}

/**
 * Android execution adapter for [ExchangeRateEnrichmentWorkflow].
 *
 * Contains no bank, parsing, or financial matching rules.
 */
class ExchangeRateEnrichmentWorker(
    appContext: Context,
    params: WorkerParameters,
    private val enricher: PendingExchangeRateEnricher,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        try {
            enricher.enrichPending()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }

    class Factory(
        private val enricher: () -> PendingExchangeRateEnricher,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == ExchangeRateEnrichmentWorker::class.java.name) {
                ExchangeRateEnrichmentWorker(appContext, workerParameters, enricher())
            } else {
                null
            }
    }

    companion object {
        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                WorkManagerExchangeRateEnrichmentScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                WorkManagerExchangeRateEnrichmentScheduler.workRequest(),
            )
        }
    }
}
