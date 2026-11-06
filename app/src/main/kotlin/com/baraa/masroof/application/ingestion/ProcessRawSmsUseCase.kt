package com.baraa.masroof.application.ingestion

import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository

/**
 * Bulk-maintenance wrapper: production only calls [reparseAndStore]. Capture and processing use
 * [CaptureBankSmsUseCase] and [ProcessStoredSmsUseCase] directly; tests capture-then-process with the `ingest` helper.
 */
class ProcessRawSmsUseCase(
    internal val capture: CaptureBankSmsUseCase,
    internal val processStored: ProcessStoredSmsUseCase,
) {
    constructor(
        rawSmsRepository: RawSmsRepository,
        parsedEventRepository: ParsedEventRepository,
        bankSmsRegistry: BankSmsRegistry,
        ownershipDiscovery: OwnershipDiscoveryService? = null,
        reconciliation: TransactionReconciliationService? = null,
        reviewQueueUpdater: ReviewQueueUpdater? = null,
        ingestionReviewService: IngestionReviewService? = null,
        appLogService: AppLogService? = null,
        reviewRepository: ReviewRepository? = null,
    ) : this(
        capture = CaptureBankSmsUseCase(rawSmsRepository, bankSmsRegistry, appLogService),
        processStored = ProcessStoredSmsUseCase(
            rawSmsRepository = rawSmsRepository,
            parsedEventRepository = parsedEventRepository,
            bankSmsRegistry = bankSmsRegistry,
            ownershipDiscovery = ownershipDiscovery,
            reconciliation = reconciliation,
            reviewQueueUpdater = reviewQueueUpdater,
            ingestionReviewService = ingestionReviewService,
            appLogService = appLogService,
            reviewRepository = reviewRepository,
        ),
    )

    /** Bulk-maintenance parse-only path; derived processing is intentionally deferred. */
    suspend fun reparseAndStore(rawSms: RawSms, historicalBatch: Boolean = false): SmsIngestionResult =
        processStored.reparseAndStore(rawSms, historicalBatch)
}
