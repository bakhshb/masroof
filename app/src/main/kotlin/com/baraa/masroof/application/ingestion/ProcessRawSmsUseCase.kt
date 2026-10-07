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
 * Compatibility facade: [CaptureBankSmsUseCase] (durable RawSms) followed immediately
 * by [ProcessStoredSmsUseCase] (parse and derived processing) in the same call.
 *
 * Idempotent: duplicates are not re-parsed. Callers that must survive process death
 * between capture and processing should use the two use cases directly.
 */
class ProcessRawSmsUseCase(
    private val capture: CaptureBankSmsUseCase,
    private val processStored: ProcessStoredSmsUseCase,
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

    suspend fun ingest(rawSms: RawSms, logOutcome: Boolean = true): SmsIngestionResult =
        when (val captured = capture.capture(rawSms, logOutcome)) {
            is BankSmsCaptureResult.NotRelevant -> SmsIngestionResult.NotRelevant(reason = captured.reason)
            BankSmsCaptureResult.Duplicate -> SmsIngestionResult.Duplicate
            is BankSmsCaptureResult.Failed -> SmsIngestionResult.Failed(
                rawSmsId = null,
                message = captured.message,
                cause = captured.cause,
            )
            is BankSmsCaptureResult.Captured -> processStored.process(captured.rawSms, captured.route, logOutcome)
        }

    /** See [ProcessStoredSmsUseCase.reparseStored]. */
    suspend fun reparseStored(rawSms: RawSms): SmsIngestionResult = processStored.reparseStored(rawSms)

    /** Bulk-maintenance parse-only path; derived processing is intentionally deferred. */
    suspend fun reparseAndStore(rawSms: RawSms): SmsIngestionResult = processStored.reparseAndStore(rawSms)
}
