package com.baraa.masroof.application.sms

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.ReconciliationCompletionPolicy
import com.baraa.masroof.application.transaction.ReconciliationIncompleteException
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import java.time.Instant

/**
 * One derived pass over the historical retry set.
 *
 * Loads the retry set's ParsedEvents, then runs ownership discovery, scoped
 * reconciliation, and review refresh once. It does not reparse SMS text and does
 * not scan unrelated history. The set is cleared only after the report's failed
 * count is zero and the required review update has finished. A nonzero failed
 * count leaves the set in place and fails the attempt. A thrown failure does too.
 */
class HistoricalDerivedRecovery(
    private val parsedEventRepository: ParsedEventRepository,
    private val processingRetryRepository: ProcessingRetryRepository,
    private val ownershipDiscovery: OwnershipDiscoveryService? = null,
    private val reconciliation: TransactionReconciliationService? = null,
    private val reviewQueueUpdater: ReviewQueueUpdater? = null,
    private val rawSmsRepository: RawSmsRepository? = null,
    private val appLogService: AppLogService? = null,
) {
    suspend fun recoverPending() {
        val ids = processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH)
        if (ids.isEmpty()) return
        val discovery = ownershipDiscovery
        if (discovery != null) {
            for (record in parsedEventRepository.listByRawSmsIds(ids)) {
                if (record.event.parseStatus == ParseStatus.NON_FINANCIAL) continue
                discovery.observe(record.event, record.details.loanType)
            }
        }
        val report = reconciliation?.reconcileAffectedRawSmsIds(idsInArrivalOrder(ids))
        if (report != null && reviewQueueUpdater != null) {
            reviewQueueUpdater.applyReport(report)
        }
        if (report != null && !ReconciliationCompletionPolicy.isComplete(report)) {
            val masked = AppLogFormatting.maskId(report.failedRawSmsIds.firstOrNull() ?: ids.first())
            appLogService?.error(
                AppLogCategories.INGEST,
                "Derived reconciliation incomplete failures=${report.summary.failed} id=$masked " +
                    "retry_state=retained (ReconciliationIncompleteException)",
            )
            throw ReconciliationIncompleteException(
                failureCount = report.summary.failed,
                maskedRawSmsId = masked,
            )
        }
        processingRetryRepository.clear(ids)
        if (processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH).isNotEmpty()) {
            throw IllegalStateException("historical recovery still has retry rows")
        }
    }

    private suspend fun idsInArrivalOrder(ids: List<String>): List<String> {
        val receivedAt = rawSmsRepository?.getByIds(ids)?.associate { it.id to it.receivedAt } ?: return ids
        return ids.sortedWith(compareBy({ receivedAt[it] ?: Instant.MAX }, { it }))
    }
}
