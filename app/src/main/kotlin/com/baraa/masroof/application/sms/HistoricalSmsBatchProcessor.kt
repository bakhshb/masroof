package com.baraa.masroof.application.sms

import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.ExchangeRateEnrichmentWorkflow
import com.baraa.masroof.application.transaction.ReconciliationSummary
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import kotlinx.coroutines.CancellationException

/**
 * Batch orchestration for historical inbox import.
 *
 * Each row is captured, parsed, and persisted on its own ([Batch.ingest]); derived work runs
 * once per batch ([Batch.finish]): ownership discovery for the events the batch stored, one
 * reconciliation pass, one review refresh, and one exchange-rate enrichment pass. Live
 * processing stays per message.
 *
 * Derived steps are best-effort: a failure never removes captured RawSms/ParsedEvent evidence,
 * and the next batch (or reprocessing) reconciles it.
 */
class HistoricalSmsBatchProcessor(
    private val capture: CaptureBankSmsUseCase,
    private val processStored: ProcessStoredSmsUseCase,
    private val ownershipDiscovery: OwnershipDiscoveryService? = null,
    private val reconciliation: TransactionReconciliationService? = null,
    private val reviewQueueUpdater: ReviewQueueUpdater? = null,
    private val exchangeRateEnrichment: ExchangeRateEnrichmentWorkflow? = null,
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
         * Runs the batch's derived pass once. Returns the reconciliation summary, or null when
         * reconciliation is not wired or failed.
         */
        suspend fun finish(): ReconciliationSummary? {
            check(!finished) { "Batch already finished" }
            finished = true
            storedEvents.forEach { discoverOwnership(it) }
            val report = try {
                reconciliation?.reconcileBatchDetailed()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return null
            try {
                reviewQueueUpdater?.applyReport(report)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Review persistence must not fail imported evidence; the next refresh retries.
            }
            try {
                exchangeRateEnrichment?.enrichPending()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Enrichment is best-effort; pending rows are retried by the next run.
            }
            return report.summary
        }

        private suspend fun discoverOwnership(stored: StoredEvent) {
            val discovery = ownershipDiscovery ?: return
            try {
                discovery.observe(stored.event, stored.loanType)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Discovery is best-effort; evidence stays for the next pass.
            }
        }
    }

    private class StoredEvent(val event: ParsedEvent, val loanType: LoanType?)

    private fun SmsIngestionResult.storedEvent(): StoredEvent? =
        when (this) {
            is SmsIngestionResult.Parsed -> StoredEvent(event, details.loanType)
            is SmsIngestionResult.ReviewRequired -> event?.let { StoredEvent(it, details.loanType) }
            is SmsIngestionResult.NonFinancial -> event?.let { StoredEvent(it, details.loanType) }
            else -> null
        }
}
