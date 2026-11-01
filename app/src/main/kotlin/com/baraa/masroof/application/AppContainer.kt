package com.baraa.masroof.application

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.DelegatingWorkerFactory
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.baraa.masroof.application.backup.DatabaseBackupService
import com.baraa.masroof.application.backup.DatabaseRestoreRecovery
import com.baraa.masroof.application.commitment.CommitmentFromTransactionService
import com.baraa.masroof.application.dashboard.DashboardService
import com.baraa.masroof.application.dashboard.DashboardLayoutPreferencesRepository
import com.baraa.masroof.application.dashboard.DashboardCommitmentsWorkflow
import com.baraa.masroof.application.dashboard.DashboardPeriodWorkflow
import com.baraa.masroof.application.dashboard.DashboardRegistryWorkflow
import com.baraa.masroof.application.dashboard.ForeignSarMarketRateProvider
import com.baraa.masroof.application.dashboard.FrankfurterForeignSarRateProvider
import com.baraa.masroof.application.dashboard.TransactionSarEquivalentResolver
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.ExplicitBankSelectionWorkflow
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewOwnershipWorkflow
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.application.settings.SettingsRegistryWorkflow
import com.baraa.masroof.application.statement.ImportStatementUseCase
import com.baraa.masroof.application.statement.StatementReconciliationService
import com.baraa.masroof.application.statement.StatementReconciliationWorkflow
import com.baraa.masroof.application.statement.statementLedgerReferences
import com.baraa.masroof.application.transaction.ExchangeRateEnrichmentWorkflow
import com.baraa.masroof.application.transaction.HistoricalMerchantRateCorrectionWorkflow
import com.baraa.masroof.application.transaction.FinancialTransactionEvidenceSyncer
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.application.transaction.TransactionIgnoreService
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.notification.NotificationCenterMetricsWorkflow
import com.baraa.masroof.application.notification.NotificationCenterService
import com.baraa.masroof.application.notification.NotificationPreferencesRepository
import com.baraa.masroof.application.theme.ThemePreferencesRepository
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.update.ApkInstaller
import com.baraa.masroof.application.update.AppUpdateService
import com.baraa.masroof.application.update.GitHubReleaseClient
import com.baraa.masroof.application.update.PendingUpdateStore
import com.baraa.masroof.application.update.UpdateCheckCoordinator
import com.baraa.masroof.application.update.UpdateCheckPreferencesRepository
import com.baraa.masroof.application.update.UpdateChecker
import com.baraa.masroof.BuildConfig
import com.baraa.masroof.application.locale.AppLocaleBootstrap
import com.baraa.masroof.application.locale.AppLocaleContextFactory
import com.baraa.masroof.application.maintenance.MaintenanceCompletionSignal
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.maintenance.ReparseAllStoredEventsResult
import com.baraa.masroof.application.maintenance.StartupMaintenance
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome
import com.baraa.masroof.application.maintenance.StoredSmsReprocessor
import com.baraa.masroof.application.maintenance.TransferIntegrityRepairCoordinator
import com.baraa.masroof.application.maintenance.TransferIntegrityRepairResult
import okhttp3.OkHttpClient
import com.baraa.masroof.application.onboarding.OnboardingOwnershipWorkflow
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsAppLocaleRepository
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.data.preferences.AndroidKeystoreGitHubTokenEncryptor
import com.baraa.masroof.data.preferences.SharedPrefsDashboardLayoutPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsGitHubTokenRepository
import com.baraa.masroof.data.preferences.SharedPrefsNotificationPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsOnboardingPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsThemePreferencesRepository
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomCommitmentRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomManualReviewResolutionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomProcessingRetryRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.repository.RoomTransactionIgnoreRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.statement.CanonicalCsvStatementParser
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.statement.StatementMatchPolicy
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.CommitmentRepository
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.ManualReviewResolutionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserCorrectionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.datasource.AndroidSmsDataSource
import com.baraa.masroof.sms.datasource.SmsDataSource
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.sms.DebugProcessHaltProbe
import com.baraa.masroof.application.sms.DebugProcessHalt
import com.baraa.masroof.application.sms.HistoricalDerivedRecovery
import com.baraa.masroof.application.sms.HistoricalDerivedRecoveryWorker
import com.baraa.masroof.application.sms.LiveCaptureRecoveryWorker
import com.baraa.masroof.application.sms.HistoricalSmsBatchProcessor
import com.baraa.masroof.application.sms.HistoricalSmsScanner
import com.baraa.masroof.application.sms.LiveSmsIntake
import com.baraa.masroof.application.sms.LiveSmsProcessingWorker
import com.baraa.masroof.application.sms.ExchangeRateEnrichmentWorker
import com.baraa.masroof.application.sms.PendingExchangeRateEnricher
import com.baraa.masroof.application.sms.WorkManagerExchangeRateEnrichmentScheduler
import com.baraa.masroof.application.sms.WorkManagerLiveSmsWorkScheduler
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Minimal manual composition root for P6–P9.
 *
 * No DI framework. Application-scoped database and repositories.
 * Does not use fallbackToDestructiveMigration.
 */
class AppContainer(
    context: Context,
    bankAdapterOverride: com.baraa.masroof.bank.BankSmsAdapter? = null,
    smsDataSourceOverride: com.baraa.masroof.sms.datasource.SmsDataSource? = null,
    restartAfterRestore: (() -> Unit)? = null,
    databaseOpenHelperFactory: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory = com.baraa.masroof.data.room.PreservingOpenHelperFactory(),
) {
    private val appContext = context.applicationContext

    /** Room must not open until an interrupted restore has chosen a valid file. */
    private val databaseRestoreRecovered: Unit = DatabaseRestoreRecovery.recover(appContext)

    val clock: InstantClock = InstantClock.System

    private val applicationJob = SupervisorJob()
    val applicationScope: CoroutineScope =
        CoroutineScope(applicationJob + Dispatchers.IO)

    val appLogService: AppLogService = AppLogService(appContext)

    /** Keeps a device-test seed invisible to live processing until the review row exists. */
    suspend fun <R> withDatabaseTransaction(block: suspend () -> R): R =
        databaseAccessGate.withAccess { database.withTransaction(block) }

    val databaseAccessGate: DatabaseAccessGate = DatabaseAccessGate()

    private val bundledPatternBanks = appContext.assets.list("bank-patterns").orEmpty().filter { it.endsWith(".patterns.json") }.sorted().flatMap { file ->
        com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec.decode(
            appContext.assets.open("bank-patterns/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }).banks
    }
    // Restore recovery has finished and this container has not admitted work yet.
    private val messagePatternStore = com.baraa.masroof.data.patterns.MessagePatternStore(appContext).also {
        if (bankAdapterOverride == null) it.installMissingBanks(bundledPatternBanks, setOf(Bank.BANK_ALJAZIRA.id))
    }

    val messagePatternsWorkflow by lazy {
        com.baraa.masroof.application.patterns.MessagePatternsWorkflow(
            messagePatternStore, databaseAccessGate, bundledBanks = bundledPatternBanks,
            requiredBundledBankIds = setOf(Bank.BANK_ALJAZIRA.id),
            appLogService = appLogService,
            activation = com.baraa.masroof.application.patterns.PatternApprovalWorkflow(messagePatternStore, databaseAccessGate,
                accountRegistryRepository, ownershipConfirmationService, cardRegistryRepository),
            pendingPreview = com.baraa.masroof.application.patterns.PendingPatternsWorkflow(
                messagePatternStore, databaseAccessGate,
                reviewRepository, rawSmsRepository, parsedEventRepository, userCorrectionRepository,
                financialTransactionRepository, bankSmsRegistry, historicalSmsBatchProcessor,
                inTransaction = { work -> withDatabaseTransaction(work) },
                markParseFailed = processStoredSmsUseCase::recordExhaustedDerivedProcessing,
                onChanged = { maintenanceCompletionSignal.notifyCompleted(); liveSmsIntake.schedulePendingProcessing() }))
    }

    internal val database: MasroofDatabase =
        databaseRestoreRecovered.let {
            Room.databaseBuilder(
                appContext,
                MasroofDatabase::class.java,
                MasroofDatabase.NAME,
            )
                .openHelperFactory(databaseOpenHelperFactory)
                .build()
        }

    val rawSmsRepository: RawSmsRepository =
        RoomRawSmsRepository(database.rawSmsDao(), accessGate = databaseAccessGate)

    val parsedEventRepository: ParsedEventRepository =
        RoomParsedEventRepository(database.parsedEventDao(), accessGate = databaseAccessGate)

    val accountRegistryRepository: AccountRegistryRepository =
        RoomAccountRegistryRepository.from(database, databaseAccessGate)

    val cardRegistryRepository: CardRegistryRepository =
        RoomCardRegistryRepository.from(database, databaseAccessGate)

    val loanRegistryRepository: LoanRegistryRepository =
        RoomLoanRegistryRepository.from(database, databaseAccessGate)

    val commitmentRepository: CommitmentRepository =
        RoomCommitmentRepository.from(database, databaseAccessGate)

    val financialTransactionRepository: FinancialTransactionRepository =
        RoomFinancialTransactionRepository(
            dao = database.financialTransactionDao(),
            parsedEventDao = database.parsedEventDao(),
            accessGate = databaseAccessGate,
        )

    val reviewRepository: ReviewRepository =
        RoomReviewRepository(database.reviewItemDao(), accessGate = databaseAccessGate)

    val processingRetryRepository: ProcessingRetryRepository =
        RoomProcessingRetryRepository(database.processingRetryDao(), accessGate = databaseAccessGate)

    val userCorrectionRepository: UserCorrectionRepository =
        RoomUserCorrectionRepository(database.userCorrectionDao(), accessGate = databaseAccessGate)

    val applicationContext: Context get() = appContext

    val localizedApplicationContext: Context
        get() = AppLocaleContextFactory.wrap(
            appContext,
            AppLocaleBootstrap.readStoredLanguageTag(appContext),
        )

    val onboardingPreferencesRepository: OnboardingPreferencesRepository =
        SharedPrefsOnboardingPreferencesRepository(
            appContext.getSharedPreferences(
                SharedPrefsOnboardingPreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    val appLocaleRepository: AppLocaleRepository =
        SharedPrefsAppLocaleRepository(
            appContext.getSharedPreferences(
                SharedPrefsAppLocaleRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    val themePreferencesRepository: ThemePreferencesRepository =
        SharedPrefsThemePreferencesRepository(
            appContext.getSharedPreferences(
                SharedPrefsThemePreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    val dashboardLayoutPreferencesRepository: DashboardLayoutPreferencesRepository =
        SharedPrefsDashboardLayoutPreferencesRepository(
            appContext.getSharedPreferences(
                SharedPrefsDashboardLayoutPreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    val chartDataWorkflow = com.baraa.masroof.application.dashboard.ChartDataWorkflow(
        com.baraa.masroof.data.preferences.SharedPrefsChartDataPreferencesRepository(
            appContext.getSharedPreferences(com.baraa.masroof.data.preferences.SharedPrefsChartDataPreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE)))

    val notificationPreferencesRepository: NotificationPreferencesRepository =
        SharedPrefsNotificationPreferencesRepository(
            appContext.getSharedPreferences(
                SharedPrefsNotificationPreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    val notificationCenterService: NotificationCenterService =
        NotificationCenterService(
            preferencesRepository = notificationPreferencesRepository,
        )

    val ownershipDiscoveryService: OwnershipDiscoveryService =
        OwnershipDiscoveryService(
            accountRegistry = accountRegistryRepository,
            cardRegistry = cardRegistryRepository,
            loanRegistry = loanRegistryRepository,
        )

    val ownershipResolver: OwnershipResolver =
        OwnershipResolver(
            accountRegistry = accountRegistryRepository,
            cardRegistry = cardRegistryRepository,
            loanRegistry = loanRegistryRepository,
        )

    val ownershipConfirmationService: OwnershipConfirmationService =
        OwnershipConfirmationService(
            accountRegistry = accountRegistryRepository,
            cardRegistry = cardRegistryRepository,
            loanRegistry = loanRegistryRepository,
        )

    val effectiveParsedEventProvider: EffectiveParsedEventProvider =
        EffectiveParsedEventProvider(
            parsedEventRepository = parsedEventRepository,
            userCorrectionRepository = userCorrectionRepository,
        )

    private val automaticInterpretationWriteGuard by lazy {
        com.baraa.masroof.application.interpretation.AutomaticInterpretationWriteGuard(
            effectiveParsedEventProvider,reviewRepository,financialTransactionRepository,
            inferenceCurrent = { interpretationService?.isInferenceCurrent(it) != false },
            inTransaction = { mutation -> databaseAccessGate.withAccess { database.withTransaction { mutation() } } },
            patternsCurrent = com.baraa.masroof.application.interpretation.PatternInterpretationWritePolicy(
                messagePatternStore, accountRegistryRepository, cardRegistryRepository)::isCurrent)
    }

    val transactionReconciliationService: TransactionReconciliationService =
        TransactionReconciliationService(
            parsedEventRepository = parsedEventRepository,
            rawSmsRepository = rawSmsRepository,
            financialTransactionRepository = financialTransactionRepository,
            ownershipResolver = ownershipResolver,
            ownershipConfirmationService = ownershipConfirmationService,
            effectiveParsedEventProvider = effectiveParsedEventProvider,
            reviewRepository = reviewRepository,
            automaticWrite = { expected, write -> automaticInterpretationWriteGuard.write(expected,write) },
        )

    val reviewQueueUpdater: ReviewQueueUpdater =
        ReviewQueueUpdater(
            reviewRepository = reviewRepository,
            financialTransactionRepository = financialTransactionRepository,
            clock = clock,
        )

    val ingestionReviewService: IngestionReviewService =
        IngestionReviewService(
            reviewRepository = reviewRepository,
            clock = clock,
        )

    val processingRecovery: ProcessingRecovery =
        ProcessingRecovery(
            processingRetryRepository = processingRetryRepository,
            reviewRepository = reviewRepository,
            ingestionReviewService = ingestionReviewService,
            clock = clock,
        )

    val manualReviewResolutionRepository: ManualReviewResolutionRepository =
        RoomManualReviewResolutionRepository(
            database = database,
            financialTransactionRepository = financialTransactionRepository,
            accessGate = databaseAccessGate,
        )

    val reviewWorkflowService: ReviewWorkflowService =
        ReviewWorkflowService(
            reviewRepository = reviewRepository,
            userCorrectionRepository = userCorrectionRepository,
            financialTransactionRepository = financialTransactionRepository,
            rawSmsRepository = rawSmsRepository,
            ownershipResolver = ownershipResolver,
            ownershipConfirmationService = ownershipConfirmationService,
            effectiveParsedEventProvider = effectiveParsedEventProvider,
            parsedEventRepository = parsedEventRepository,
            reconciliationService = transactionReconciliationService,
            reviewQueueUpdater = reviewQueueUpdater,
            manualReviewResolutionRepository = manualReviewResolutionRepository,
            clock = clock,
            appLogService = appLogService,
            processingRecovery = processingRecovery,
            onHistoricalRetry = {
                HistoricalDerivedRecoveryWorker.enqueue(WorkManager.getInstance(appContext))
            },
        )

    val reviewOwnershipWorkflow: ReviewOwnershipWorkflow =
        ReviewOwnershipWorkflow(
            cardRegistryRepository = cardRegistryRepository,
            ownershipConfirmationService = ownershipConfirmationService,
        )

    val settingsRegistryWorkflow: SettingsRegistryWorkflow =
        SettingsRegistryWorkflow(
            cardRegistryRepository = cardRegistryRepository,
            accountRegistryRepository = accountRegistryRepository,
            loanRegistryRepository = loanRegistryRepository,
            ownershipConfirmationService = ownershipConfirmationService,
            parsedEvents = parsedEventRepository,
            inTransaction = { block -> withDatabaseTransaction(block) },
            transactions = financialTransactionRepository,
            patternBoundAccounts = {
                databaseAccessGate.withAccess { messagePatternStore.load() }.banks.flatMap { bank -> bank.currentPatterns }
                    .flatMap { it.factContract?.accountBindings.orEmpty() }
                    .filter { it.role != com.baraa.masroof.engine.bank.patterns.PatternAccountRole.CARD }
                    .map { com.baraa.masroof.domain.model.AccountReference(Bank(it.bank.value), it.last4) }.toSet()
            },
            patternBoundCards = {
                databaseAccessGate.withAccess { messagePatternStore.load() }.banks.flatMap { bank -> bank.currentPatterns }
                    .flatMap { it.factContract?.accountBindings.orEmpty() }
                    .filter { it.role == com.baraa.masroof.engine.bank.patterns.PatternAccountRole.CARD }
                    .map { com.baraa.masroof.domain.model.CardReference(Bank(it.bank.value), it.last4) }.toSet()
            },
        )

    val settingsCommitmentsWorkflow: SettingsCommitmentsWorkflow =
        SettingsCommitmentsWorkflow(
            commitmentRepository = commitmentRepository,
            clock = clock,
        )

    val dashboardPeriodWorkflow: DashboardPeriodWorkflow =
        DashboardPeriodWorkflow(clock = clock)

    val dashboardRegistryWorkflow: DashboardRegistryWorkflow =
        DashboardRegistryWorkflow(
            cardRegistryRepository = cardRegistryRepository,
            accountRegistryRepository = accountRegistryRepository,
        )

    val notificationCenterMetricsWorkflow: NotificationCenterMetricsWorkflow =
        NotificationCenterMetricsWorkflow(
            reviewRepository = reviewRepository,
            cardRegistryRepository = cardRegistryRepository,
            accountRegistryRepository = accountRegistryRepository,
        )

    val onboardingOwnershipWorkflow: OnboardingOwnershipWorkflow =
        OnboardingOwnershipWorkflow(
            accountRegistryRepository = accountRegistryRepository,
            cardRegistryRepository = cardRegistryRepository,
            ownershipConfirmationService = ownershipConfirmationService,
            reviewRepository = reviewRepository,
        )

    val transactionReclassificationService: TransactionReclassificationService =
        TransactionReclassificationService(
            financialTransactionRepository = financialTransactionRepository,
            effectiveParsedEventProvider = effectiveParsedEventProvider,
            ownershipResolver = ownershipResolver,
            ownershipConfirmationService = ownershipConfirmationService,
            appLogService = appLogService,
            persistence = com.baraa.masroof.data.repository.RoomTransactionReclassificationRepository(
                database, financialTransactionRepository, databaseAccessGate),
            afterDecisionCommitted = { debugProcessHaltProbe.afterDurableWrite(DebugProcessHalt.RECLASSIFIED) },
        )

    val transactionIgnoreService: TransactionIgnoreService =
        TransactionIgnoreService(
            persistence = RoomTransactionIgnoreRepository(database, databaseAccessGate, afterDelete = {
                debugProcessHaltProbe.afterDurableWrite(DebugProcessHalt.IGNORE_PENDING)
            }),
            clock = clock,
            appLogService = appLogService,
        )

    val commitmentFromTransactionService: CommitmentFromTransactionService =
        CommitmentFromTransactionService(
            commitmentRepository = commitmentRepository,
            financialTransactionRepository = financialTransactionRepository,
        )

    val dashboardCommitmentsWorkflow: DashboardCommitmentsWorkflow =
        DashboardCommitmentsWorkflow(
            commitmentFromTransactionService = commitmentFromTransactionService,
            commitmentRepository = commitmentRepository,
        )

    private val updateHttpClient: OkHttpClient = GitHubReleaseClient.defaultHttpClient()

    private val sarEquivalentResolver: TransactionSarEquivalentResolver =
        TransactionSarEquivalentResolver(
            marketRateProvider = FrankfurterForeignSarRateProvider(updateHttpClient),
        )

    private val exchangeRateEnrichmentScheduler = WorkManagerExchangeRateEnrichmentScheduler { WorkManager.getInstance(appContext) }

    /** Writer of missing applied exchange rates; dashboard loads stay read-only. */
    val exchangeRateEnrichmentWorkflow: ExchangeRateEnrichmentWorkflow =
        ExchangeRateEnrichmentWorkflow(
            financialTransactionRepository = financialTransactionRepository,
            parsedEventRepository = parsedEventRepository,
            rawSmsRepository = rawSmsRepository,
            sarEquivalentResolver = sarEquivalentResolver,
            appLogService = appLogService,
        )

    /** Confirmed replacement of a frozen historical-merchant rate. Does not run during enrichment. */
    val historicalMerchantRateCorrectionWorkflow: HistoricalMerchantRateCorrectionWorkflow =
        HistoricalMerchantRateCorrectionWorkflow(
            financialTransactionRepository = financialTransactionRepository,
            parsedEventRepository = parsedEventRepository,
            rawSmsRepository = rawSmsRepository,
            sarEquivalentResolver = sarEquivalentResolver,
            appLogService = appLogService,
        )

    val dashboardService: DashboardService =
        DashboardService(
            financialTransactionRepository = financialTransactionRepository,
            reviewRepository = reviewRepository,
            parsedEventRepository = parsedEventRepository,
            rawSmsRepository = rawSmsRepository,
            appLocaleRepository = appLocaleRepository,
            accountRegistryRepository = accountRegistryRepository,
            cardRegistryRepository = cardRegistryRepository,
            loanRegistryRepository = loanRegistryRepository,
            commitmentRepository = commitmentRepository,
            // Dashboard loads never call the network; market rates are written by enrichment.
            sarEquivalentResolver = TransactionSarEquivalentResolver(ForeignSarMarketRateProvider { _, _ -> null }),
        )

    val transactionRestoreService: TransactionRestoreService =
        TransactionRestoreService(
            reviewRepository = reviewRepository,
            financialTransactionRepository = financialTransactionRepository,
            reconciliation = transactionReconciliationService,
            reclassification = transactionReclassificationService,
            clock = clock,
            appLogService = appLogService,
            reviewQueueUpdater = reviewQueueUpdater,
            processingRecovery = processingRecovery,
            onHistoricalRetry = {
                HistoricalDerivedRecoveryWorker.enqueue(WorkManager.getInstance(appContext))
            },
        )

    val interpretationService = if (bankAdapterOverride==null)
        com.baraa.masroof.application.interpretation.BankInterpretationService(appContext,rawSmsRepository,parsedEventRepository,
            userCorrectionRepository,reviewRepository,financialTransactionRepository,applicationScope,appLogService,
            patterns = messagePatternStore::load,
            onFeedbackChanged = { maintenanceCompletionSignal.notifyCompleted(); if(analyticsDelegate.isInitialized()) analytics.request() }) else null

    private val bankSmsRegistry: BankSmsRegistry =
        BankSmsRegistry(
            adapters = listOfNotNull(bankAdapterOverride),
            configuredAdapters = if (bankAdapterOverride != null) ({ emptyList() }) else
                com.baraa.masroof.application.interpretation.ConfiguredPatternAdapters(messagePatternStore,
                    assistedBanks = { requireNotNull(interpretationService).assistedBanks(it) },
                    assistedInterpret = { input, snapshot, bankId ->
                        requireNotNull(interpretationService).interpret(input, snapshot, bankId) })::snapshot,
        )

    val statementReconciliationWorkflow: StatementReconciliationWorkflow =
        StatementReconciliationWorkflow(
            importStatement = ImportStatementUseCase(
                parser = CanonicalCsvStatementParser(),
                reconciliation = StatementReconciliationService(
                    financialTransactionRepository = financialTransactionRepository,
                    referencesFor = { transactions ->
                        statementLedgerReferences(parsedEventRepository, transactions)
                    },
                ),
            ),
            knownBankIds = {
                val registered = bankSmsRegistry.banks().map { it.id }
                val accounts = accountRegistryRepository.listAll().map { it.bank.id }
                (registered + accounts)
                    .filter { it.isNotBlank() && it != Bank.UNKNOWN.id }
                    .toSet()
            },
            ownedAccounts = {
                accountRegistryRepository.listAll()
                    .asSequence()
                    .filter { it.ownership == OwnershipStatus.OWNED }
                    .filter { it.bank != Bank.UNKNOWN && it.maskedNumber.isNotBlank() }
                    .map { StatementMatchPolicy.QualifiedAccount(it.bank, it.maskedNumber.trim()) }
                    .toSet()
            },
        )

    private val captureBankSmsUseCase: CaptureBankSmsUseCase =
        CaptureBankSmsUseCase(
            rawSmsRepository = rawSmsRepository,
            bankSmsRegistry = bankSmsRegistry,
            appLogService = appLogService,
        )

    private val debugProcessHaltProbe: DebugProcessHaltProbe =
        DebugProcessHaltProbe(appContext.filesDir)

    val processStoredSmsUseCase: ProcessStoredSmsUseCase =
        ProcessStoredSmsUseCase(
            rawSmsRepository = rawSmsRepository,
            parsedEventRepository = parsedEventRepository,
            bankSmsRegistry = bankSmsRegistry,
            ownershipDiscovery = ownershipDiscoveryService,
            reconciliation = transactionReconciliationService,
            reviewQueueUpdater = reviewQueueUpdater,
            ingestionReviewService = ingestionReviewService,
            appLogService = appLogService,
            exchangeRateEnrichmentScheduler = exchangeRateEnrichmentScheduler,
            processingRecovery = processingRecovery,
            reviewRepository = reviewRepository,
            debugProcessHalt = debugProcessHaltProbe,
            patternProcessing = com.baraa.masroof.application.ingestion.PatternEvidenceProtection(
                reviewRepository, userCorrectionRepository, financialTransactionRepository, parsedEventRepository,
                inTransaction = { work -> withDatabaseTransaction(work) })::run,
        )

    val explicitBankSelectionWorkflow: ExplicitBankSelectionWorkflow =
        ExplicitBankSelectionWorkflow(
            reviewRepository = reviewRepository,
            rawSmsRepository = rawSmsRepository,
            parsedEventRepository = parsedEventRepository,
            bankSmsRegistry = bankSmsRegistry,
            processStored = processStoredSmsUseCase,
            clock = clock,
        )

    val processRawSmsUseCase: ProcessRawSmsUseCase =
        ProcessRawSmsUseCase(
            capture = captureBankSmsUseCase,
            processStored = processStoredSmsUseCase,
        )

    val historicalDerivedRecovery: HistoricalDerivedRecovery =
        HistoricalDerivedRecovery(
            parsedEventRepository = parsedEventRepository,
            processingRetryRepository = processingRetryRepository,
            ownershipDiscovery = ownershipDiscoveryService,
            reconciliation = transactionReconciliationService,
            reviewQueueUpdater = reviewQueueUpdater,
            rawSmsRepository = rawSmsRepository,
            appLogService = appLogService,
        )

    val liveSmsIntake: LiveSmsIntake =
        LiveSmsIntake(
            captureBankSms = captureBankSmsUseCase,
            scheduler = WorkManagerLiveSmsWorkScheduler { WorkManager.getInstance(appContext) },
            rawSmsRepository = rawSmsRepository,
            reviewRepository = reviewRepository,
            processingRetryRepository = processingRetryRepository,
            appLogService = appLogService,
            batchRecoveryScheduler = {
                HistoricalDerivedRecoveryWorker.enqueue(WorkManager.getInstance(appContext))
            },
            debugProcessHalt = debugProcessHaltProbe,
            captureRecoveryScheduler = { receivedAfter ->
                LiveCaptureRecoveryWorker.enqueue(WorkManager.getInstance(appContext), receivedAfter)
            },
            clock = clock,
        )

    val workerFactory: WorkerFactory =
        DelegatingWorkerFactory().apply {
            addFactory(LiveSmsProcessingWorker.Factory({ processStoredSmsUseCase }, appLogService) { maintenanceCompletionSignal.notifyCompleted() })
            addFactory(
                ExchangeRateEnrichmentWorker.Factory {
                    PendingExchangeRateEnricher {
                        if (exchangeRateEnrichmentWorkflow.enrichPending().persisted > 0) maintenanceCompletionSignal.notifyCompleted()
                    }
                },
            )
            addFactory(
                HistoricalDerivedRecoveryWorker.Factory(
                    recovery = { historicalDerivedRecovery },
                    appLogService = appLogService,
                ),
            )
            addFactory(
                LiveCaptureRecoveryWorker.Factory(
                    scanner = { historicalSmsScanner },
                    startupReady = { awaitStartupMaintenance() == StartupMaintenanceOutcome.READY },
                    appLogService = appLogService,
                ) { maintenanceCompletionSignal.notifyCompleted() },
            )
        }

    val smsDataSource: SmsDataSource =
        smsDataSourceOverride ?: AndroidSmsDataSource(appContext.contentResolver)

    val bankSmsImportWorkflow by lazy {
        com.baraa.masroof.application.sms.BankSmsImportWorkflow(historicalSmsScanner,
            onChanged = { maintenanceCompletionSignal.notifyCompleted() })
    }

    val historicalSmsBatchProcessor: HistoricalSmsBatchProcessor = HistoricalSmsBatchProcessor(
        capture = captureBankSmsUseCase,
        processStored = processStoredSmsUseCase,
        ownershipDiscovery = ownershipDiscoveryService,
        reconciliation = transactionReconciliationService,
        reviewQueueUpdater = reviewQueueUpdater,
        exchangeRateEnrichmentScheduler = exchangeRateEnrichmentScheduler,
        processingRecovery = processingRecovery,
        batchRecoveryScheduler = {
            HistoricalDerivedRecoveryWorker.enqueue(WorkManager.getInstance(appContext))
        },
        appLogService = appLogService,
    )

    val historicalSmsScanner: HistoricalSmsScanner = HistoricalSmsScanner(
        dataSource = smsDataSource,
        batchProcessor = historicalSmsBatchProcessor,
        appLogService = appLogService,
        coverageCompleted = { after, started -> databaseAccessGate.withAccess {
            com.baraa.masroof.application.analytics.AnalysisCoverageStore.recordScan(appContext, after, started)
        } },
    )

    /**
     * Discovers ownership candidates from all already-persisted ParsedEvents.
     */
    suspend fun discoverFromStoredEvents(): Int {
        var count = 0
        for (record in parsedEventRepository.listAll()) {
            ownershipDiscoveryService.observe(record.event, record.details.loanType)
            count++
        }
        return count
    }

    suspend fun reconcileStoredEvents() =
        transactionReconciliationService.reconcileStoredEvents()

    suspend fun refreshReviewQueue() =
        reviewWorkflowService.refreshReviewQueue()

    private val storedSmsReprocessor: StoredSmsReprocessor by lazy {
        StoredSmsReprocessor(
            rawSmsRepository = rawSmsRepository,
            processRawSms = processRawSmsUseCase,
            refreshDerivedState = {
                discoverFromStoredEvents()
                val report = transactionReconciliationService.reconcileStoredEventsDetailed()
                FinancialTransactionEvidenceSyncer.syncMerchants(
                    transactions = financialTransactionRepository.listAll(),
                    parsedRecords = parsedEventRepository.listAll(),
                    repository = financialTransactionRepository,
                )
                reviewWorkflowService.completeReconciliation(report)
                enrichExchangeRatesBestEffort()
            },
            appLogService = appLogService,
        )
    }

    /**
     * Re-parses every stored RawSms (including rows that never produced a
     * ParsedEvent). Parser upgrades apply to the backlog without duplicating evidence.
     */
    suspend fun reparseAllStoredEvents(): ReparseAllStoredEventsResult =
        storedSmsReprocessor.reprocessAll()

    @Volatile internal var shutdownStage: String = "OPEN"
        private set
    internal val startupCancelled: Boolean get() = startupMaintenanceJob?.isCancelled == true

    @Synchronized fun close() {
        if (shutdownStage == "CLOSED") return
        shutdownStage = "STARTUP_DRAINING"
        // Room cancellation can finish the coroutine before its first SQLite connection opens.
        // Drain startup normally so close cannot take the write lock during that callback.
        runBlocking(Dispatchers.IO) {
            startupMaintenanceJob?.join()
        }
        // Release readers that were waiting for startup before draining their service mutexes.
        startupMaintenanceOutcome.value = StartupMaintenanceOutcome.BLOCKED
        shutdownStage = "ANALYTICS_DRAINING"
        stopAnalytics()
        shutdownStage = "LEARNING_DRAINING"
        interpretationService?.close()
        shutdownStage = "APPLICATION_DRAINING"
        runBlocking(Dispatchers.IO) {
            applicationJob.complete()
            applicationJob.join()
        }
        // WorkManager jobs outlive applicationScope; finish admitted work before closing their stores.
        runBlocking(Dispatchers.IO) {
            databaseAccessGate.retireAndDrain()
        }
        shutdownStage = "DATABASE_CLOSING"
        database.close()
        startupMaintenanceOutcome.value = StartupMaintenanceOutcome.BLOCKED
        shutdownStage = "CLOSED"
    }

    val databaseBackupService: DatabaseBackupService by lazy {
        DatabaseBackupService(
            appContext = appContext,
            database = database,
            closeDatabase = { database.close() },
            databaseAccessGate = databaseAccessGate,
            appVersionName = BuildConfig.VERSION_NAME,
            appLogService = appLogService,
            restartProcess = restartAfterRestore,
            beforeValidatedInstall = {
                stopAnalytics()
                interpretationService?.invalidateForRestore()
                com.baraa.masroof.application.analytics.AnalyticsNotifications.clear(appContext)
            },
        )
    }

    val githubTokenRepository: com.baraa.masroof.application.update.GitHubTokenRepository =
        SharedPrefsGitHubTokenRepository(
            prefs = appContext.getSharedPreferences(
                SharedPrefsGitHubTokenRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
            encryptor = AndroidKeystoreGitHubTokenEncryptor(),
        )

    val pendingUpdateStore: PendingUpdateStore by lazy {
        PendingUpdateStore(
            appContext.getSharedPreferences(
                PendingUpdateStore.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )
    }

    val updateCheckPreferencesRepository: UpdateCheckPreferencesRepository by lazy {
        UpdateCheckPreferencesRepository(
            appContext.getSharedPreferences(
                UpdateCheckPreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )
    }

    val appUpdateService: AppUpdateService by lazy {
        AppUpdateService(
            context = appContext,
            tokenRepository = githubTokenRepository,
            releaseClient =
                GitHubReleaseClient(
                    httpClient = updateHttpClient,
                    owner = BuildConfig.GITHUB_OWNER,
                    repo = BuildConfig.GITHUB_REPO,
                ),
            updateChecker = UpdateChecker(installedVersionCode = BuildConfig.VERSION_CODE),
            preferencesRepository = updateCheckPreferencesRepository,
            appLogService = appLogService,
        )
    }

    val updateCheckCoordinator: UpdateCheckCoordinator by lazy {
        UpdateCheckCoordinator(
            appUpdateService = appUpdateService,
            pendingUpdateStore = pendingUpdateStore,
            preferencesRepository = updateCheckPreferencesRepository,
            appLogService = appLogService,
        )
    }

    val apkInstaller: ApkInstaller by lazy {
        ApkInstaller(appContext)
    }

    private val transferIntegrityRepairCoordinator: TransferIntegrityRepairCoordinator by lazy {
        TransferIntegrityRepairCoordinator(
            prefs = appContext.getSharedPreferences(
                MaintenancePreferences.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
            appLogService = appLogService,
            repairStoredTransfers = {
                val report = transactionReconciliationService.repairLegacyTransfersDetailed()
                reviewQueueUpdater.applyReport(report)
                TransferIntegrityRepairResult(failedCount = report.summary.failed)
            },
            completionSignal = maintenanceCompletionSignal,
        )
    }

    /** Emits when background maintenance changed stored data; open screens reload on it. */
    val maintenanceCompletionSignal: MaintenanceCompletionSignal = MaintenanceCompletionSignal()

    private val startupMaintenance: StartupMaintenance by lazy {
        StartupMaintenance(transferIntegrityRepair = transferIntegrityRepairCoordinator)
    }

    private val startupMaintenanceOutcome = kotlinx.coroutines.flow.MutableStateFlow<StartupMaintenanceOutcome?>(null)
    private var startupMaintenanceJob: Job? = null

    /**
     * Startup releases financial UI only after correctness-blocking maintenance succeeds.
     */
    val interpretationProvenanceWorkflow by lazy { com.baraa.masroof.application.interpretation.InterpretationProvenanceWorkflow(effectiveParsedEventProvider, reviewRepository,
        feedback = { interpretationService?.feedbackStatus(it) }) }
    val learningInsightsWorkflow by lazy { com.baraa.masroof.application.interpretation.LearningInsightsWorkflow(
        interpretationService, parsedEventRepository, financialTransactionRepository, reviewRepository) }
    val messageReviewWorkflow by lazy { com.baraa.masroof.application.interpretation.MessageReviewWorkflow(
        reviewRepository, rawSmsRepository, parsedEventRepository) }

    private val analyticsDelegate = lazy { com.baraa.masroof.application.analytics.AnalyticsWorkflow(this, appContext) }
    val analytics get() = analyticsDelegate.value
    fun stopAnalytics() { if (analyticsDelegate.isInitialized()) analytics.close() }
    fun runStartupMaintenance() {
        startupMaintenanceJob = applicationScope.launch {
            val outcome = runStartupMaintenanceAttempt()
            startupMaintenanceOutcome.value = outcome
            if (outcome == StartupMaintenanceOutcome.READY) {
                runPostStartupBackgroundWork()
            }
        }
    }

    /** Retry a previously blocked startup maintenance attempt from the gated UI. */
    suspend fun retryStartupMaintenance(): StartupMaintenanceOutcome {
        val outcome = runStartupMaintenanceAttempt()
        startupMaintenanceOutcome.value = outcome
        if (outcome == StartupMaintenanceOutcome.READY) {
            runPostStartupBackgroundWork()
        }
        return outcome
    }

    private suspend fun runStartupMaintenanceAttempt(): StartupMaintenanceOutcome =
        try {
            startupMaintenance.runBlockingPhase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogService.warn(
                AppLogCategories.PARSE,
                "Blocking startup maintenance failed: ${e.javaClass.simpleName}",
            )
            StartupMaintenanceOutcome.BLOCKED
        }

    private suspend fun runPostStartupBackgroundWork() {
        interpretationService?.startLearning(database.invalidationTracker)
        analytics.start()
        liveSmsIntake.schedulePendingProcessing()
        enrichExchangeRatesBestEffort()
    }

    private suspend fun enrichExchangeRatesBestEffort() {
        try {
            if (exchangeRateEnrichmentWorkflow.enrichPending().persisted > 0) maintenanceCompletionSignal.notifyCompleted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogService.warn(AppLogCategories.TRANSACTION, "Exchange-rate enrichment failed: ${e.javaClass.simpleName}")
        }
    }

    /** Test barrier: initial pending scheduling must finish before a capture halt is armed. */
    suspend fun awaitPostStartupBackgroundWorkForTesting() {
        checkNotNull(startupMaintenanceJob) { "Startup maintenance has not been launched" }.join()
    }

    /** Returns the safety outcome of the initial startup maintenance attempt. */
    suspend fun awaitStartupMaintenance(): StartupMaintenanceOutcome =
        startupMaintenanceOutcome.filterNotNull().first().let {
            if (shutdownStage == "OPEN") it else StartupMaintenanceOutcome.BLOCKED
        }
}
