package com.baraa.masroof.application.sms

import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.DerivedProcessingStage
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLog
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.ReconciliationCompletionPolicy
import com.baraa.masroof.application.transaction.ReconciliationSummary
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Outcome of one historical batch's derived pass. Parsing stays per row;
 * ownership, reconciliation, and review refresh stay one pass for the batch.
 */
sealed interface HistoricalBatchDerivedResult {
    data class Succeeded(
        val summary: ReconciliationSummary,
    ) : HistoricalBatchDerivedResult

    data class Incomplete(
        val stage: DerivedProcessingStage,
    ) : HistoricalBatchDerivedResult
}

/**
 * Batch orchestration for historical inbox import.
 *
 * Each row is captured, parsed, and persisted on its own ([Batch.ingest]); derived work runs
 * once per batch ([Batch.finish]): ownership discovery for the events the batch stored, one
 * reconciliation of those RawSms ids, one review refresh, and one scheduled exchange-rate
 * enrichment pass (network-bound, so it never runs inline). Transfer counterparts already in storage are loaded only inside the matcher window.
 * Live processing stays per message.
 *
 * A correctness-blocking derived failure keeps the captured evidence and marks the affected
 * financial rows in one transaction, then schedules one batch recovery. A reconciliation
 * report with a nonzero failed count is that failure even when nothing was thrown: finish
 * does not return success and does not clear the retry set. Exchange-rate enrichment
 * scheduling stays best-effort.
 */
class HistoricalSmsBatchProcessor(
    private val capture: CaptureBankSmsUseCase,
    private val processStored: ProcessStoredSmsUseCase,
    private val ownershipDiscovery: OwnershipDiscoveryService,
    private val reconciliation: TransactionReconciliationService,
    private val reviewQueueUpdater: ReviewQueueUpdater,
    private val exchangeRateEnrichmentScheduler: ExchangeRateEnrichmentScheduler,
    private val processingRecovery: ProcessingRecovery,
    private val batchRecoveryScheduler: HistoricalBatchRecoveryScheduler,
    private val appLogService: AppLog,
) {
    fun startBatch(bankScope: Bank? = null, bankScopes: Set<Bank>? = null): Batch {
        require(bankScope == null || bankScopes == null)
        val scopes = bankScopes ?: bankScope?.let(::setOf)
        scopes?.let { require(it.isNotEmpty()); it.forEach(capture::requireSupportedBank) }
        return Batch(scopes)
    }

    inner class Batch internal constructor(private val bankScopes: Set<Bank>?) {
        private val storedEvents = mutableListOf<StoredEvent>()
        private var finished = false
        var reconciliationSummary: ReconciliationSummary? = null
            private set

        /** Number of ParsedEvents this batch stored and will observe in [finish]. */
        val storedEventCount: Int get() = storedEvents.size

        suspend fun ingest(rawSms: RawSms): SmsIngestionResult {
            check(!finished) { "Batch already finished" }
            val result = when (val captured = capture.capture(rawSms, logOutcome = false, bankScopes = bankScopes)) {
                is BankSmsCaptureResult.NotRelevant -> SmsIngestionResult.NotRelevant(reason = captured.reason)
                BankSmsCaptureResult.Duplicate -> SmsIngestionResult.Duplicate
                is BankSmsCaptureResult.Failed -> SmsIngestionResult.Failed(
                    rawSmsId = null,
                    message = captured.message,
                    cause = captured.cause,
                )
                is BankSmsCaptureResult.Captured ->
                    processStored.parseAndStore(captured.rawSms, captured.route, logOutcome = false)
            }
            result.storedEvent(rawSms.receivedAt)?.let(storedEvents::add)
            return result
        }

        /** Already captured, eligible pending evidence uses the same parser and one batch-derived pass. */
        suspend fun reprocess(rawSms: RawSms): SmsIngestionResult {
            check(!finished) { "Batch already finished" }
            val result = processStored.reparseAndStore(rawSms, historicalBatch = true)
            result.storedEvent(rawSms.receivedAt)?.let(storedEvents::add)
            return result
        }

        /**
         * Runs ownership, reconciliation, and review refresh once for the batch.
         * Evidence already stored is kept. A correctness-blocking failure is
         * [HistoricalBatchDerivedResult.Incomplete] only after the full retry set is saved.
         * A failed marker write throws and leaves this batch unfinished so [finish] can be retried.
         */
        suspend fun finish(): HistoricalBatchDerivedResult {
            check(!finished) { "Batch already finished" }
            val derived = runDerivedPass()
            finished = true
            scheduleExchangeRates()
            return derived
        }

        private suspend fun runDerivedPass(): HistoricalBatchDerivedResult {
            if (!discoverOwnership()) {
                markAffected()
                return HistoricalBatchDerivedResult.Incomplete(DerivedProcessingStage.OWNERSHIP_DISCOVERY)
            }
            val report = try {
                reconciliation.reconcileAffectedRawSmsIds(affectedRawSmsIds())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markAffected()
                logIncomplete(
                    stage = DerivedProcessingStage.RECONCILIATION,
                    failureCount = null,
                    rawSmsId = storedEvents.firstOrNull()?.event?.rawSmsId,
                    retryState = "marked",
                    errorClass = e.javaClass.simpleName,
                )
                return HistoricalBatchDerivedResult.Incomplete(DerivedProcessingStage.RECONCILIATION)
            }
            reconciliationSummary = report.summary
            try {
                reviewQueueUpdater.applyReport(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markAffected()
                logIncomplete(
                    stage = DerivedProcessingStage.REVIEW_UPDATE,
                    failureCount = report.summary.failed,
                    rawSmsId = report.failedRawSmsIds.firstOrNull()
                        ?: storedEvents.firstOrNull()?.event?.rawSmsId,
                    retryState = "retained",
                    errorClass = e.javaClass.simpleName,
                )
                return HistoricalBatchDerivedResult.Incomplete(DerivedProcessingStage.REVIEW_UPDATE)
            }
            if (!ReconciliationCompletionPolicy.isComplete(report)) {
                markAffected()
                logIncomplete(
                    stage = DerivedProcessingStage.RECONCILIATION,
                    failureCount = report.summary.failed,
                    rawSmsId = report.failedRawSmsIds.firstOrNull()
                        ?: storedEvents.firstOrNull()?.event?.rawSmsId,
                    retryState = "marked",
                    errorClass = "ReconciliationIncompleteException",
                )
                return HistoricalBatchDerivedResult.Incomplete(DerivedProcessingStage.RECONCILIATION)
            }
            clearRecovered()
            return HistoricalBatchDerivedResult.Succeeded(report.summary)
        }

        private fun logIncomplete(
            stage: DerivedProcessingStage,
            failureCount: Int?,
            rawSmsId: String?,
            retryState: String,
            errorClass: String,
        ) {
            val failures = failureCount?.let { " failures=$it" }.orEmpty()
            val masked = rawSmsId?.let(AppLogFormatting::maskId) ?: "none"
            appLogService.error(
                AppLogCategories.INGEST,
                "Derived ${stage.name.lowercase()} incomplete$failures id=$masked " +
                    "retry_state=$retryState ($errorClass)",
            )
        }

        private suspend fun discoverOwnership(): Boolean {
            for (stored in storedEvents) {
                try {
                    ownershipDiscovery.observe(stored.event, stored.loanType)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return false
                }
            }
            return true
        }

        private suspend fun markAffected() {
            val affected = storedEvents
                .filter { it.event.parseStatus != ParseStatus.NON_FINANCIAL }
                .map { it.event.rawSmsId }
            if (!processingRecovery.markExhaustedBatch(affected)) return
            scheduleBatchRecovery()
        }

        private suspend fun clearRecovered() {
            processingRecovery.clearAll(storedEvents.map { it.event.rawSmsId })
        }

        private fun scheduleBatchRecovery() {
            try {
                batchRecoveryScheduler.schedule()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The retry set is durable; startup enqueues the one batch worker.
            }
        }

        private fun scheduleExchangeRates() {
            try {
                exchangeRateEnrichmentScheduler.schedule()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Scheduling is best-effort; pending rows are retried by the next run.
            }
        }

        private fun affectedRawSmsIds(): List<String> =
            storedEvents
                .sortedWith(compareBy({ it.receivedAt }, { it.event.rawSmsId }))
                .map { it.event.rawSmsId }
                .distinct()
    }

    private class StoredEvent(
        val event: ParsedEvent,
        val loanType: LoanType?,
        val receivedAt: Instant,
    )

    private fun SmsIngestionResult.storedEvent(receivedAt: Instant): StoredEvent? =
        when (this) {
            is SmsIngestionResult.Parsed -> StoredEvent(event, details.loanType, receivedAt)
            is SmsIngestionResult.DerivedIncomplete -> StoredEvent(event, details.loanType, receivedAt)
            is SmsIngestionResult.ReviewRequired ->
                event?.let { StoredEvent(it, details.loanType, receivedAt) }
            is SmsIngestionResult.NonFinancial ->
                event?.let { StoredEvent(it, details.loanType, receivedAt) }
            else -> null
        }
}
