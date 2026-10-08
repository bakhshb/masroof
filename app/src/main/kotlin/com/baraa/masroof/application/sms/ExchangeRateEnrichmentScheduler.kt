package com.baraa.masroof.application.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
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
 * [ExistingWorkPolicy.KEEP] collapses schedules while work is waiting and no worker is inside
 * [ExchangeRateEnrichmentWorker.doWork]. A schedule that arrives during [doWork] is remembered
 * by [ExchangeRateEnrichmentGate]: the active worker reads the pending set again, or, once that
 * worker has passed its final check, a follow-up is queued with
 * [ExistingWorkPolicy.APPEND_OR_REPLACE] so WorkManager cannot drop it while the finishing
 * worker is still [WorkInfo.State.RUNNING].
 */
class WorkManagerExchangeRateEnrichmentScheduler(
    private val workManager: () -> WorkManager,
) : ExchangeRateEnrichmentScheduler {
    override fun schedule() {
        when (val claim = ExchangeRateEnrichmentGate.claimForSchedule()) {
            ScheduleClaim.COALESCED -> Unit
            ScheduleClaim.START -> enqueueClaim(claim) { ExchangeRateEnrichmentWorker.enqueue(workManager()) }
            ScheduleClaim.FOLLOW_UP -> enqueueClaim(claim) { enqueueDurableFollowUp(workManager()) }
        }
    }

    private fun enqueueClaim(claim: ScheduleClaim, enqueue: () -> Unit) {
        try {
            enqueue()
        } catch (e: CancellationException) {
            ExchangeRateEnrichmentGate.enqueueFailed(claim)
            throw e
        } catch (e: Exception) {
            ExchangeRateEnrichmentGate.enqueueFailed(claim)
            throw e
        }
    }

    /**
     * Queues work that still runs if the finishing worker has not left [WorkInfo.State.RUNNING].
     * [ExistingWorkPolicy.KEEP] drops that request. Once the unique work is already finished,
     * [ExistingWorkPolicy.KEEP] replaces it instead of growing a chain.
     */
    private fun enqueueDurableFollowUp(workManager: WorkManager) {
        val stillActive = workManager
            .getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
            .get(ENQUEUE_WAIT_SECONDS, TimeUnit.SECONDS)
            .any { !it.state.isFinished }
        if (stillActive) {
            ExchangeRateEnrichmentWorker.enqueueFollowUp(workManager)
        } else {
            ExchangeRateEnrichmentWorker.enqueue(workManager)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "exchange-rate-enrichment"
        const val WORK_TAG = "exchange-rate-enrichment"
        const val BACKOFF_DELAY_SECONDS = 30L
        internal const val ENQUEUE_WAIT_SECONDS = 15L

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
                if (!ExchangeRateEnrichmentGate.finishPass(seen)) {
                    ExchangeRateEnrichmentGate.awaitSuccessPauseForTests()
                    return Result.success()
                }
            }
        } catch (e: CancellationException) {
            if (ExchangeRateEnrichmentGate.abandon(seen)) {
                try {
                    enqueueFollowUp(WorkManager.getInstance(applicationContext))
                } catch (enqueueError: CancellationException) {
                    ExchangeRateEnrichmentGate.enqueueFailed(ScheduleClaim.FOLLOW_UP)
                    throw enqueueError
                } catch (_: Exception) {
                    ExchangeRateEnrichmentGate.enqueueFailed(ScheduleClaim.FOLLOW_UP)
                }
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
            awaitQueued(
                workManager.enqueueUniqueWork(
                    WorkManagerExchangeRateEnrichmentScheduler.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    WorkManagerExchangeRateEnrichmentScheduler.workRequest(),
                ),
            )
        }

        fun enqueueFollowUp(workManager: WorkManager) {
            awaitQueued(
                workManager.enqueueUniqueWork(
                    WorkManagerExchangeRateEnrichmentScheduler.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    WorkManagerExchangeRateEnrichmentScheduler.workRequest(),
                ),
            )
        }

        private fun awaitQueued(operation: Operation) {
            operation.result.get(
                WorkManagerExchangeRateEnrichmentScheduler.ENQUEUE_WAIT_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }
}

/**
 * Coalesces enrichment schedules with the worker that is already reading pending rows.
 *
 * [ExistingWorkPolicy.KEEP] ignores a new unique-work request while the current worker is
 * still [WorkInfo.State.RUNNING]. Clearing the in-process flag before WorkManager records
 * success opens that window: KEEP drops the request, and the flag can stay set with no
 * worker left to clear it. [ScheduleClaim.FOLLOW_UP] is that window. The active pass still
 * loops when a schedule arrives before the final check.
 */
internal object ExchangeRateEnrichmentGate {
    private val lock = Any()
    private var requested = 0
    private var phase = GatePhase.IDLE

    @Volatile
    private var successPauseForTests: (() -> Unit)? = null

    fun claimForSchedule(): ScheduleClaim = synchronized(lock) {
        requested++
        when (phase) {
            GatePhase.ACTIVE -> ScheduleClaim.COALESCED
            GatePhase.COMPLETING -> {
                phase = GatePhase.ACTIVE
                ScheduleClaim.FOLLOW_UP
            }
            GatePhase.IDLE -> {
                phase = GatePhase.ACTIVE
                ScheduleClaim.START
            }
        }
    }

    /**
     * The enqueue that [claim] started did not leave a worker that will clear [phase].
     * A later [claimForSchedule] must be able to start work again.
     */
    fun enqueueFailed(claim: ScheduleClaim) {
        synchronized(lock) {
            when (claim) {
                ScheduleClaim.START -> if (phase == GatePhase.ACTIVE) phase = GatePhase.IDLE
                ScheduleClaim.FOLLOW_UP -> if (phase == GatePhase.ACTIVE) phase = GatePhase.COMPLETING
                ScheduleClaim.COALESCED -> Unit
            }
        }
    }

    fun markRunning() {
        synchronized(lock) { phase = GatePhase.ACTIVE }
    }

    fun observedRequest(): Int = synchronized(lock) { requested }

    /**
     * @return true when a schedule arrived during the pass that just finished,
     * so the worker must read pending rows again.
     */
    fun finishPass(seen: Int): Boolean = synchronized(lock) {
        if (requested != seen) return true
        phase = GatePhase.COMPLETING
        false
    }

    /**
     * Leaves the phase active when a schedule arrived during [seen], so the follow-up
     * run still coalesces. Otherwise the worker is in its completion window.
     */
    fun abandon(seen: Int): Boolean = synchronized(lock) {
        val followUp = requested != seen
        phase = if (followUp) GatePhase.ACTIVE else GatePhase.COMPLETING
        followUp
    }

    fun setSuccessPauseForTests(block: (() -> Unit)?) {
        successPauseForTests = block
    }

    fun awaitSuccessPauseForTests() {
        successPauseForTests?.invoke()
    }

    fun resetForTests() {
        synchronized(lock) {
            requested = 0
            phase = GatePhase.IDLE
        }
        successPauseForTests = null
    }
}

internal enum class ScheduleClaim {
    COALESCED,
    START,
    FOLLOW_UP,
}

private enum class GatePhase {
    IDLE,
    ACTIVE,
    COMPLETING,
}
