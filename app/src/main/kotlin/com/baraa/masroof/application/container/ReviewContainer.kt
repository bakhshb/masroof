package com.baraa.masroof.application.container

import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.interpretation.AutomaticInterpretationWriteGuard
import com.baraa.masroof.application.interpretation.PatternInterpretationWritePolicy
import com.baraa.masroof.application.onboarding.OnboardingOwnershipWorkflow
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewOwnershipWorkflow
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.transaction.TransactionIgnoreService
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.application.transaction.LedgerInputLoader
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.application.sms.DebugProcessHalt
import com.baraa.masroof.data.repository.RoomManualReviewResolutionRepository
import com.baraa.masroof.data.repository.RoomTransactionIgnoreRepository
import com.baraa.masroof.data.repository.RoomTransactionReclassificationRepository
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.ManualReviewResolutionRepository

/** Ownership, reconciliation, the review queue and the manual decisions that change the ledger. */
class ReviewContainer(
    private val storage: StorageContainer,
    private val learning: LearningContainer,
    onHistoricalRetry: () -> Unit,
) {
    val ownershipDiscoveryService: OwnershipDiscoveryService =
        OwnershipDiscoveryService(
            accountRegistry = storage.accountRegistryRepository,
            cardRegistry = storage.cardRegistryRepository,
            loanRegistry = storage.loanRegistryRepository,
        )

    val ownershipResolver: OwnershipResolver =
        OwnershipResolver(
            accountRegistry = storage.accountRegistryRepository,
            cardRegistry = storage.cardRegistryRepository,
            loanRegistry = storage.loanRegistryRepository,
        )

    val ownershipConfirmationService: OwnershipConfirmationService =
        OwnershipConfirmationService(
            accountRegistry = storage.accountRegistryRepository,
            cardRegistry = storage.cardRegistryRepository,
            loanRegistry = storage.loanRegistryRepository,
        )

    val effectiveParsedEventProvider: EffectiveParsedEventProvider =
        EffectiveParsedEventProvider(
            parsedEventRepository = storage.parsedEventRepository,
            userDecisionRepository = storage.userDecisionRepository,
        )

    private val automaticInterpretationWriteGuard by lazy {
        AutomaticInterpretationWriteGuard(
            effectiveParsedEventProvider, storage.reviewRepository, storage.userDecisionRepository,
            storage.financialTransactionRepository,
            inferenceCurrent = { learning.interpretationService?.isInferenceCurrent(it) != false },
            inTransaction = { mutation -> storage.withDatabaseTransaction(mutation) },
            patternsCurrent = PatternInterpretationWritePolicy(
                storage.messagePatternStore, storage.accountRegistryRepository, storage.cardRegistryRepository)::isCurrent)
    }

    val transactionReconciliationService: TransactionReconciliationService =
        TransactionReconciliationService(
            rawSmsRepository = storage.rawSmsRepository,
            financialTransactionRepository = storage.financialTransactionRepository,
            ownershipResolver = ownershipResolver,
            ownershipConfirmationService = ownershipConfirmationService,
            effectiveParsedEventProvider = effectiveParsedEventProvider,
            reviewRepository = storage.reviewRepository,
            userDecisionRepository = storage.userDecisionRepository,
            automaticWrite = { expected, write -> automaticInterpretationWriteGuard.write(expected, write) },
            inTransaction = { write -> storage.withDatabaseTransaction(write) },
        )

    /** The derived ledger's inputs, for the plan 3.2 shadow comparison export. */
    val ledgerInputLoader: LedgerInputLoader =
        LedgerInputLoader(
            rawSmsRepository = storage.rawSmsRepository,
            financialTransactionRepository = storage.financialTransactionRepository,
            reviewRepository = storage.reviewRepository,
            userDecisionRepository = storage.userDecisionRepository,
            effectiveParsedEventProvider = effectiveParsedEventProvider,
            ownershipResolver = ownershipResolver,
        )

    val reviewQueueUpdater: ReviewQueueUpdater =
        ReviewQueueUpdater(
            reviewRepository = storage.reviewRepository,
            financialTransactionRepository = storage.financialTransactionRepository,
            clock = storage.clock,
        )

    val ingestionReviewService: IngestionReviewService =
        IngestionReviewService(
            reviewRepository = storage.reviewRepository,
            clock = storage.clock,
        )

    val processingRecovery: ProcessingRecovery =
        ProcessingRecovery(
            processingRetryRepository = storage.processingRetryRepository,
            reviewRepository = storage.reviewRepository,
            userDecisionRepository = storage.userDecisionRepository,
            ingestionReviewService = ingestionReviewService,
            clock = storage.clock,
        )

    val manualReviewResolutionRepository: ManualReviewResolutionRepository =
        RoomManualReviewResolutionRepository(
            database = storage.database,
            financialTransactionRepository = storage.financialTransactionRepository,
            accessGate = storage.databaseAccessGate,
        )

    val reviewWorkflowService: ReviewWorkflowService =
        ReviewWorkflowService(
            reviewRepository = storage.reviewRepository,
            userCorrectionRepository = storage.userCorrectionRepository,
            financialTransactionRepository = storage.financialTransactionRepository,
            rawSmsRepository = storage.rawSmsRepository,
            ownershipResolver = ownershipResolver,
            ownershipConfirmationService = ownershipConfirmationService,
            effectiveParsedEventProvider = effectiveParsedEventProvider,
            parsedEventRepository = storage.parsedEventRepository,
            reconciliationService = transactionReconciliationService,
            reviewQueueUpdater = reviewQueueUpdater,
            manualReviewResolutionRepository = manualReviewResolutionRepository,
            userDecisionRepository = storage.userDecisionRepository,
            clock = storage.clock,
            appLogService = storage.appLogService,
            processingRecovery = processingRecovery,
            onHistoricalRetry = onHistoricalRetry,
        )

    val reviewOwnershipWorkflow: ReviewOwnershipWorkflow =
        ReviewOwnershipWorkflow(
            cardRegistryRepository = storage.cardRegistryRepository,
            ownershipConfirmationService = ownershipConfirmationService,
        )

    val onboardingOwnershipWorkflow: OnboardingOwnershipWorkflow =
        OnboardingOwnershipWorkflow(
            accountRegistryRepository = storage.accountRegistryRepository,
            cardRegistryRepository = storage.cardRegistryRepository,
            ownershipConfirmationService = ownershipConfirmationService,
            reviewRepository = storage.reviewRepository,
        )

    val transactionReclassificationService: TransactionReclassificationService =
        TransactionReclassificationService(
            financialTransactionRepository = storage.financialTransactionRepository,
            effectiveParsedEventProvider = effectiveParsedEventProvider,
            ownershipResolver = ownershipResolver,
            ownershipConfirmationService = ownershipConfirmationService,
            appLogService = storage.appLogService,
            persistence = RoomTransactionReclassificationRepository(
                storage.database, storage.financialTransactionRepository, storage.databaseAccessGate),
            afterDecisionCommitted = { storage.debugProcessHaltProbe.afterDurableWrite(DebugProcessHalt.RECLASSIFIED) },
        )

    val transactionIgnoreService: TransactionIgnoreService =
        TransactionIgnoreService(
            persistence = RoomTransactionIgnoreRepository(storage.database, storage.databaseAccessGate, afterDelete = {
                storage.debugProcessHaltProbe.afterDurableWrite(DebugProcessHalt.IGNORE_PENDING)
            }),
            clock = storage.clock,
            appLogService = storage.appLogService,
        )

    val transactionRestoreService: TransactionRestoreService =
        TransactionRestoreService(
            reviewRepository = storage.reviewRepository,
            userDecisionRepository = storage.userDecisionRepository,
            financialTransactionRepository = storage.financialTransactionRepository,
            reconciliation = transactionReconciliationService,
            reclassification = transactionReclassificationService,
            clock = storage.clock,
            appLogService = storage.appLogService,
            reviewQueueUpdater = reviewQueueUpdater,
            processingRecovery = processingRecovery,
            onHistoricalRetry = onHistoricalRetry,
        )

    /** Discovers ownership candidates from all already-persisted ParsedEvents. */
    suspend fun discoverFromStoredEvents(): Int {
        var count = 0
        for (record in storage.parsedEventRepository.listAll()) {
            ownershipDiscoveryService.observe(record.event, record.details.loanType)
            count++
        }
        return count
    }
}
