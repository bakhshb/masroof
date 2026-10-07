package com.baraa.masroof.application.sms

import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.DerivedProcessingStage
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.ExchangeRateEnrichmentWorkflow
import com.baraa.masroof.application.transaction.ReconciliationSummary
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import kotlinx.coroutines.CancellationException

/**
 * Outcome of one historical batch's derived pass. Parsing stays per row;
 * ownership, reconciliation, and review refresh stay one pass for the batch.
 */
sealed interface HistoricalBatchDerivedResult {
    data class Succeeded(
        val summary: ReconciliationSummary?,
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
 * reconciliation pass, one review refresh, and one exchange-rate enrichment pass. Live
 * processing stays per message.
 *
 * A correctness-blocking derived failure keeps the captured evidence and marks the affected
 * financial rows in one transaction, then schedules one batch recovery. Exchange-rate
 * enrichment stays best-effort.
 */
class HistoricalSmsBatchProcessor(
    private val capture: CaptureBankSmsUseCase,
    private val processStored: ProcessStoredSmsUseCase,
    private val ownershipDiscovery: OwnershipDiscoveryService? = null,
    private val reconciliation: TransactionReconciliationService? = null,
    private val reviewQueueUpdater: ReviewQueueUpdater? = null,
    private val exchangeRateEnrichment: ExchangeRateEnrichmentWorkflow? = null,
    private val processingRecovery: ProcessingRecovery? = null,
    private val batchRecoveryScheduler: HistoricalBatchRecoveryScheduler? = null,
) {
    fun startBatch(): Batch = Batch()

    inner class Batch internal constructor() {
        private val storedEvents = mutableListOf<StoredEvent>()
        private var finished = false

        /** Number of ParsedEvents this batch stored and will observe in [finish]. */
        val storedEventCount: Int get() = storedEvents.size

        suspend fun ingest(rawSms: RawSms): SmsIngestionResult {
            check(!finished) { "Batch already finished" }
            val result = when (val captured = capture.capture(rawSms, logOutcome = false)) {
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
            result.storedEvent()?.let(storedEvents::add)
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
            enrichExchangeRates()
            return derived
        }

        private suspend fun runDerivedPass(): HistoricalBatchDerivedResult {
            if (!discoverOwnership()) {
                markAffected()
                return HistoricalBatchDerivedResult.Incomplete(DerivedProcessingStage.OWNERSHIP_DISCOVERY)
            }
            val report = try {
                reconciliation?.reconcileBatchDetailed()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                markAffected()
                return HistoricalBatchDerivedResult.Incomplete(DerivedProcessingStage.RECONCILIATION)
            }
            if (report != null && reviewQueueUpdater != null) {
                try {
                    reviewQueueUpdater.applyReport(report)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    markAffected()
                    return HistoricalBatchDerivedResult.Incomplete(DerivedProcessingStage.REVIEW_UPDATE)
                }
            }
            clearRecovered()
            return HistoricalBatchDerivedResult.Succeeded(report?.summary)
        }

        private suspend fun discoverOwnership(): Boolean {
            val discovery = ownershipDiscovery ?: return true
            for (stored in storedEvents) {
                try {
                    discovery.observe(stored.event, stored.loanType)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return false
                }
            }
            return true
        }

        private suspend fun markAffected() {
            val recovery = processingRecovery
                ?: throw IllegalStateException("processing recovery is required when derived work fails")
            val affected = storedEvents
                .filter { it.event.parseStatus != ParseStatus.NON_FINANCIAL }
                .map { it.event.rawSmsId }
            if (!recovery.markExhaustedBatch(affected)) return
            scheduleBatchRecovery()
        }

        private suspend fun clearRecovered() {
            val recovery = processingRecovery ?: return
            recovery.clearAll(storedEvents.map { it.event.rawSmsId })
        }

        private fun scheduleBatchRecovery() {
            val scheduler = batchRecoveryScheduler ?: return
            try {
                scheduler.schedule()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The retry set is durable; startup enqueues the one batch worker.
            }
        }

        private suspend fun enrichExchangeRates() {
            try {
                exchangeRateEnrichment?.enrichPending()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Enrichment is best-effort; pending rows are retried by the next run.
            }
        }
    }

    private class StoredEvent(val event: ParsedEvent, val loanType: LoanType?)

    private fun SmsIngestionResult.storedEvent(): StoredEvent? =
        when (this) {
            is SmsIngestionResult.Parsed -> StoredEvent(event, details.loanType)
            is SmsIngestionResult.DerivedIncomplete -> StoredEvent(event, details.loanType)
            is SmsIngestionResult.ReviewRequired -> event?.let { StoredEvent(it, details.loanType) }
            is SmsIngestionResult.NonFinancial -> event?.let { StoredEvent(it, details.loanType) }
            else -> null
        }
}
