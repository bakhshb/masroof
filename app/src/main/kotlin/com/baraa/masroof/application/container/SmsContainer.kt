package com.baraa.masroof.application.container

import androidx.work.WorkManager
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.PatternEvidenceProtection
import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.interpretation.ConfiguredPatternAdapters
import com.baraa.masroof.application.maintenance.ReparseAllStoredEventsResult
import com.baraa.masroof.application.maintenance.StoredSmsReprocessor
import com.baraa.masroof.application.review.ExplicitBankSelectionWorkflow
import com.baraa.masroof.application.sms.BankSmsImportWorkflow
import com.baraa.masroof.application.sms.HistoricalDerivedRecovery
import com.baraa.masroof.application.sms.HistoricalSmsBatchProcessor
import com.baraa.masroof.application.sms.HistoricalSmsScanner
import com.baraa.masroof.application.sms.LiveCaptureRecoveryWorker
import com.baraa.masroof.application.sms.LiveSmsIntake
import com.baraa.masroof.application.sms.WorkManagerExchangeRateEnrichmentScheduler
import com.baraa.masroof.application.sms.WorkManagerLiveSmsWorkScheduler
import com.baraa.masroof.application.statement.ImportStatementUseCase
import com.baraa.masroof.application.statement.StatementReconciliationService
import com.baraa.masroof.application.statement.StatementReconciliationWorkflow
import com.baraa.masroof.application.statement.statementLedgerReferences
import com.baraa.masroof.application.transaction.FinancialTransactionEvidenceSyncer
import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.data.statement.CanonicalCsvStatementParser
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.statement.StatementMatchPolicy
import com.baraa.masroof.sms.datasource.AndroidSmsDataSource
import com.baraa.masroof.sms.datasource.SmsDataSource
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The SMS pipeline: bank routing, capture, processing, historical scan, live intake, reprocessing and statements.
 * Several objects are shared with other containers through the [maintenance], [review] and [dashboard] handed in.
 */
class SmsContainer(
    private val storage: StorageContainer,
    maintenance: MaintenanceContainer,
    learning: LearningContainer,
    private val review: ReviewContainer,
    private val dashboard: DashboardContainer,
    bankAdapterOverride: BankSmsAdapter?,
    smsDataSourceOverride: SmsDataSource?,
    private val enqueueHistoricalRecovery: () -> Unit,
) {
    private val appContext = storage.appContext

    val bankSmsRegistry: BankSmsRegistry =
        BankSmsRegistry(
            adapters = listOfNotNull(bankAdapterOverride),
            configuredAdapters = if (bankAdapterOverride != null) ({ emptyList() }) else
                ConfiguredPatternAdapters(storage.messagePatternStore,
                    assistedBanks = { requireNotNull(learning.interpretationService).assistedBanks(it) },
                    assistedInterpret = { input, snapshot, bankId ->
                        requireNotNull(learning.interpretationService).interpret(input, bankId, snapshot) })::snapshot,
        )

    val statementReconciliationWorkflow: StatementReconciliationWorkflow =
        StatementReconciliationWorkflow(
            importStatement = ImportStatementUseCase(
                parser = CanonicalCsvStatementParser(),
                reconciliation = StatementReconciliationService(
                    financialTransactionRepository = storage.financialTransactionRepository,
                    referencesFor = { transactions ->
                        statementLedgerReferences(storage.parsedEventRepository, transactions)
                    },
                ),
            ),
            knownBankIds = {
                val registered = bankSmsRegistry.banks().map { it.id }
                val accounts = storage.accountRegistryRepository.listAll().map { it.bank.id }
                (registered + accounts)
                    .filter { it.isNotBlank() && it != Bank.UNKNOWN.id }
                    .toSet()
            },
            ownedAccounts = {
                storage.accountRegistryRepository.listAll()
                    .asSequence()
                    .filter { it.ownership == OwnershipStatus.OWNED }
                    .filter { it.bank != Bank.UNKNOWN && it.maskedNumber.isNotBlank() }
                    .map { StatementMatchPolicy.QualifiedAccount(it.bank, it.maskedNumber.trim()) }
                    .toSet()
            },
        )

    private val exchangeRateEnrichmentScheduler = WorkManagerExchangeRateEnrichmentScheduler { WorkManager.getInstance(appContext) }

    private val captureBankSmsUseCase: CaptureBankSmsUseCase =
        CaptureBankSmsUseCase(
            rawSmsRepository = storage.rawSmsRepository,
            bankSmsRegistry = bankSmsRegistry,
            appLogService = storage.appLogService,
        )

    val processStoredSmsUseCase: ProcessStoredSmsUseCase =
        ProcessStoredSmsUseCase(
            rawSmsRepository = storage.rawSmsRepository,
            parsedEventRepository = storage.parsedEventRepository,
            bankSmsRegistry = bankSmsRegistry,
            ownershipDiscovery = review.ownershipDiscoveryService,
            reconciliation = review.transactionReconciliationService,
            reviewQueueUpdater = review.reviewQueueUpdater,
            ingestionReviewService = review.ingestionReviewService,
            appLogService = storage.appLogService,
            exchangeRateEnrichmentScheduler = exchangeRateEnrichmentScheduler,
            processingRecovery = review.processingRecovery,
            reviewRepository = storage.reviewRepository,
            debugProcessHalt = storage.debugProcessHaltProbe,
            patternProcessing = PatternEvidenceProtection(
                storage.reviewRepository, storage.userCorrectionRepository, storage.financialTransactionRepository,
                storage.parsedEventRepository,
                inTransaction = { work -> storage.withDatabaseTransaction(work) })::run,
            onHistoricalRetry = enqueueHistoricalRecovery,
        )

    val explicitBankSelectionWorkflow: ExplicitBankSelectionWorkflow =
        ExplicitBankSelectionWorkflow(
            reviewRepository = storage.reviewRepository,
            userDecisionRepository = storage.userDecisionRepository,
            rawSmsRepository = storage.rawSmsRepository,
            parsedEventRepository = storage.parsedEventRepository,
            bankSmsRegistry = bankSmsRegistry,
            processStored = processStoredSmsUseCase,
            clock = storage.clock,
        )

    val processRawSmsUseCase: ProcessRawSmsUseCase =
        ProcessRawSmsUseCase(
            capture = captureBankSmsUseCase,
            processStored = processStoredSmsUseCase,
        )

    val historicalDerivedRecovery: HistoricalDerivedRecovery =
        HistoricalDerivedRecovery(
            parsedEventRepository = storage.parsedEventRepository,
            processingRetryRepository = storage.processingRetryRepository,
            ownershipDiscovery = review.ownershipDiscoveryService,
            reconciliation = review.transactionReconciliationService,
            reviewQueueUpdater = review.reviewQueueUpdater,
            rawSmsRepository = storage.rawSmsRepository,
            appLogService = storage.appLogService,
        )

    val liveSmsIntake: LiveSmsIntake =
        LiveSmsIntake(
            captureBankSms = captureBankSmsUseCase,
            scheduler = WorkManagerLiveSmsWorkScheduler { WorkManager.getInstance(appContext) },
            rawSmsRepository = storage.rawSmsRepository,
            reviewRepository = storage.reviewRepository,
            processingRetryRepository = storage.processingRetryRepository,
            appLogService = storage.appLogService,
            batchRecoveryScheduler = {
                enqueueHistoricalRecovery()
            },
            debugProcessHalt = storage.debugProcessHaltProbe,
            missedSmsScheduler = { receivedAfter ->
                LiveCaptureRecoveryWorker.enqueue(WorkManager.getInstance(appContext), receivedAfter, delaySeconds = 0)
            },
            captureRecoveryScheduler = { receivedAfter ->
                LiveCaptureRecoveryWorker.enqueue(WorkManager.getInstance(appContext), receivedAfter)
            },
            clock = storage.clock,
        )

    val smsDataSource: SmsDataSource =
        smsDataSourceOverride ?: AndroidSmsDataSource(appContext.contentResolver)

    val historicalSmsBatchProcessor: HistoricalSmsBatchProcessor = HistoricalSmsBatchProcessor(
        capture = captureBankSmsUseCase,
        processStored = processStoredSmsUseCase,
        ownershipDiscovery = review.ownershipDiscoveryService,
        reconciliation = review.transactionReconciliationService,
        reviewQueueUpdater = review.reviewQueueUpdater,
        exchangeRateEnrichmentScheduler = exchangeRateEnrichmentScheduler,
        processingRecovery = review.processingRecovery,
        batchRecoveryScheduler = {
            enqueueHistoricalRecovery()
        },
        appLogService = storage.appLogService,
    )

    val historicalSmsScanner: HistoricalSmsScanner = HistoricalSmsScanner(
        dataSource = smsDataSource,
        batchProcessor = historicalSmsBatchProcessor,
        appLogService = storage.appLogService,
        coverageCompleted = { after, started -> storage.databaseAccessGate.withAccess {
            com.baraa.masroof.application.analytics.AnalysisCoverageStore.recordScan(appContext, after, started)
        } },
    )

    val bankSmsImportWorkflow by lazy {
        BankSmsImportWorkflow(historicalSmsScanner,
            onChanged = { maintenance.completionSignal.notifyCompleted() })
    }

    private val storedSmsReprocessor: StoredSmsReprocessor by lazy {
        StoredSmsReprocessor(
            rawSmsRepository = storage.rawSmsRepository,
            processRawSms = processRawSmsUseCase,
            refreshDerivedState = {
                review.discoverFromStoredEvents()
                val report = review.transactionReconciliationService.reconcileStoredEventsDetailed()
                FinancialTransactionEvidenceSyncer.syncMerchants(
                    transactions = storage.financialTransactionRepository.listAll(),
                    parsedRecords = storage.parsedEventRepository.listAll(),
                    repository = storage.financialTransactionRepository,
                )
                review.reviewWorkflowService.completeReconciliation(report)
                dashboard.enrichExchangeRatesBestEffort()
            },
            processingRetryRepository = storage.processingRetryRepository,
            appLogService = storage.appLogService,
        )
    }

    /**
     * Re-parses every stored RawSms (including rows that never produced a
     * ParsedEvent). Parser upgrades apply to the backlog without duplicating evidence.
     */
    suspend fun reparseAllStoredEvents(): ReparseAllStoredEventsResult =
        storedSmsReprocessor.reprocessAll()

    private val missedSmsCatchUpScheduled = AtomicBoolean(false)

    /** Once per process, when the user opens the app ready to use: only a user launch brings a stopped app back. */
    fun scheduleMissedSmsCatchUp() {
        if (!storage.onboardingPreferencesRepository.isOnboardingCompleted() || !missedSmsCatchUpScheduled.compareAndSet(false, true)) return
        storage.applicationScope.launch { liveSmsIntake.scheduleMissedSmsCatchUp() }
    }
}
