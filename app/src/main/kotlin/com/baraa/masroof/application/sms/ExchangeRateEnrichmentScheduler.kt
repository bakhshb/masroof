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
 * [ExistingWorkPolicy.KEEP] collapses schedules while work is waiting. A schedule that arrives
 * while that job is already running is remembered by [ExchangeRateEnrichmentGate] and the
 * worker reads the pending set again before it finishes, so a foreign transaction created
 * mid-run is not dropped.
 */
class WorkManagerExchangeRateEnrichmentScheduler(
    private val workManager: () -> WorkManager,
) : ExchangeRateEnrichmentScheduler {
    override fun schedule() {
        ExchangeRateEnrichmentGate.schedule {
            ExchangeRateEnrichmentWorker.enqueue(workManager())
        }
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
    override suspend fun doWork(): Result {
        ExchangeRateEnrichmentGate.markRunning()
        var seen = ExchangeRateEnrichmentGate.observedRequest()
        try {
            while (true) {
                seen = ExchangeRateEnrichmentGate.observedRequest()
                enricher.enrichPending()
                if (!ExchangeRateEnrichmentGate.finishPass(seen)) return Result.success()
            }
        } catch (e: CancellationException) {
            if (ExchangeRateEnrichmentGate.abandon(seen)) {
                enqueueFollowUp(WorkManager.getInstance(applicationContext))
            }
            throw e
        } catch (_: Exception) {
            ExchangeRateEnrichmentGate.abandon(seen)
            return Result.retry()
        }
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

        fun enqueueFollowUp(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                WorkManagerExchangeRateEnrichmentScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                WorkManagerExchangeRateEnrichmentScheduler.workRequest(),
            )
        }
    }
}

/**
 * Coalesces enrichment schedules with the worker that is already reading pending rows.
 *
 * [ExistingWorkPolicy.KEEP] ignores [schedule] while unique work is [RUNNING]. This gate
 * keeps that schedule and makes the active worker pass over the pending set again.
 */
internal object ExchangeRateEnrichmentGate {
    private val lock = Any()
    private var requested = 0
    private var running = false

    fun schedule(enqueue: () -> Unit) {
        val shouldEnqueue = synchronized(lock) {
            requested++
            if (running) {
                false
            } else {
                running = true
                true
            }
        }
        if (shouldEnqueue) enqueue()
    }

    fun markRunning() {
        synchronized(lock) { running = true }
    }

    fun observedRequest(): Int = synchronized(lock) { requested }

    /**
     * @return true when a schedule arrived during the pass that just finished,
     * so the worker must read pending rows again.
     */
    fun finishPass(seen: Int): Boolean = synchronized(lock) {
        if (requested != seen) return true
        running = false
        false
    }

    /**
     * Leaves the running flag set when a schedule arrived during [seen], so the follow-up
     * run still coalesces. Clears it when nothing else was requested.
     */
    fun abandon(seen: Int): Boolean = synchronized(lock) {
        val followUp = requested != seen
        running = followUp
        followUp
    }

    fun resetForTests() {
        synchronized(lock) {
            requested = 0
            running = false
        }
    }
}
