package com.baraa.masroof.testsupport

import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.domain.model.RawSms

/** Tests only: capture then process in one call. Duplicates are not re-parsed. */
suspend fun ProcessRawSmsUseCase.ingest(rawSms: RawSms, logOutcome: Boolean = true): SmsIngestionResult =
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

/** Tests only: wires capture and processing over the same repositories, as the old convenience constructor did. */
fun processRawSmsUseCaseOf(
    rawSmsRepository: RawSmsRepository,
    parsedEventRepository: ParsedEventRepository,
    bankSmsRegistry: BankSmsRegistry,
    ownershipDiscovery: OwnershipDiscoveryService? = null,
    reconciliation: TransactionReconciliationService? = null,
    reviewQueueUpdater: ReviewQueueUpdater? = null,
    ingestionReviewService: IngestionReviewService? = null,
    appLogService: AppLogService? = null,
    reviewRepository: ReviewRepository? = null,
) = ProcessRawSmsUseCase(
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
