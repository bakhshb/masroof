package com.baraa.masroof.testsupport

import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.logging.AppLog
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository

/** Tests only: wires capture and processing over the same repositories, as the old convenience constructor did. */
fun processRawSmsUseCaseOf(
    rawSmsRepository: RawSmsRepository,
    parsedEventRepository: ParsedEventRepository,
    bankSmsRegistry: BankSmsRegistry,
    ownershipDiscovery: OwnershipDiscoveryService = noOpOwnershipDiscovery(),
    reconciliation: TransactionReconciliationService = noOpReconciliation(),
    reviewQueueUpdater: ReviewQueueUpdater = noOpReviewQueueUpdater(),
    ingestionReviewService: IngestionReviewService = noOpIngestionReviewService(),
    appLogService: AppLog = NoOpAppLog,
    userDecisionRepository: UserDecisionRepository = NoOpUserDecisionRepository,
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
        userDecisionRepository = userDecisionRepository,
        exchangeRateEnrichmentScheduler = NoOpExchangeRateEnrichmentScheduler,
        processingRecovery = noOpProcessingRecovery(),
    ),
)
