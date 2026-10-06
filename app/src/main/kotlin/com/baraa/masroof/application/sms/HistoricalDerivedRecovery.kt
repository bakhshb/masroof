package com.baraa.masroof.application.sms

import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository

/**
 * One derived pass over the historical retry set.
 *
 * Loads stored ParsedEvents, then runs ownership discovery, reconciliation, and review
 * refresh once. It does not reparse SMS text. Success clears that set in one transaction.
 * Failure leaves the set in place.
 */
class HistoricalDerivedRecovery(
    private val parsedEventRepository: ParsedEventRepository,
    private val processingRetryRepository: ProcessingRetryRepository,
    private val ownershipDiscovery: OwnershipDiscoveryService? = null,
    private val reconciliation: TransactionReconciliationService? = null,
    private val reviewQueueUpdater: ReviewQueueUpdater? = null,
) {
    suspend fun recoverPending() {
        val ids = processingRetryRepository.listUnreviewedRetryableRawSmsIds()
        if (ids.isEmpty()) return
        val discovery = ownershipDiscovery
        if (discovery != null) {
            for (record in parsedEventRepository.listByRawSmsIds(ids)) {
                if (record.event.parseStatus == ParseStatus.NON_FINANCIAL) continue
                discovery.observe(record.event, record.details.loanType)
            }
        }
        val report = reconciliation?.reconcileBatchDetailed()
        if (report != null && reviewQueueUpdater != null) {
            reviewQueueUpdater.applyReport(report)
        }
        processingRetryRepository.clear(ids)
        if (processingRetryRepository.listUnreviewedRetryableRawSmsIds().isNotEmpty()) {
            throw IllegalStateException("historical recovery still has retry rows")
        }
    }
}
