package com.baraa.masroof.application.sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.DerivedProcessingStage
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.transaction.ReconciliationIncompleteException
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Completion contract for a reconciliation report that counts failures without throwing,
 * plus a thrown reconciliation exception. These assertions fail when a returned report
 * is treated as success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProcessingCompletionFailureMatrixTest {
    private lateinit var context: Context
    private lateinit var harness: LiveSmsProcessingHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        harness = LiveSmsProcessingHarness(context)
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun liveNonthrowingConflict_doesNotFinish_andSurvivesExhaustionAndRelaunch() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms()
        assertTrue(harness.capture.capture(raw) is BankSmsCaptureResult.Captured)
        harness.nonthrowingSaveConflicts.set(Int.MAX_VALUE)
        val processStored = harness.processStored(appLogService = harness.appLog)
        val listAllBefore = harness.reconciliationListAllCalls.get()

        val outcome = processStored.process(raw.id)

        assertTrue(outcome is SmsIngestionResult.DerivedIncomplete)
        val incomplete = outcome as SmsIngestionResult.DerivedIncomplete
        assertEquals(DerivedProcessingStage.RECONCILIATION, incomplete.stage)
        assertTrue(incomplete.failureCount!! > 0)
        assertTrue(incomplete.cause is ReconciliationIncompleteException)
        assertNotNull(harness.parsedRepo.findByRawSmsId(raw.id))
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))
        assertEquals(1, harness.db.rawSmsDao().count())
        assertEquals(listAllBefore, harness.reconciliationListAllCalls.get())

        val results = (0 until LiveSmsProcessingWorker.MAX_ATTEMPTS).map { attempt ->
            worker(raw.id, attempt, processStored).doWork()
        }
        assertEquals(
            List(LiveSmsProcessingWorker.MAX_ATTEMPTS - 1) { ListenableWorker.Result.retry() } +
                ListenableWorker.Result.failure(),
            results,
        )
        assertEquals(
            listOf(raw.id),
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE),
        )
        assertTrue(
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH).isEmpty(),
        )
        val review = harness.reviewRepo.findByRawSmsId(raw.id)!!
        assertEquals(ReviewStatus.REQUIRED, review.status)
        assertEquals(listOf(IngestionReviewService.REASON_PROCESSING_ERROR), review.reasons)
        assertEquals(listAllBefore, harness.reconciliationListAllCalls.get())

        val scheduled = mutableListOf<String>()
        val historicalSchedules = AtomicInteger(0)
        val restarted = LiveSmsIntake(
            captureBankSms = harness.capture,
            scheduler = { scheduled += it },
            rawSmsRepository = harness.rawRepo,
            reviewRepository = harness.reviewRepo,
            processingRetryRepository = harness.processingRetryRepo,
            appLogService = harness.appLog,
            batchRecoveryScheduler = { historicalSchedules.incrementAndGet() },
        )
        assertEquals(1, restarted.schedulePendingProcessing())
        assertEquals(listOf(raw.id), scheduled)
        assertEquals(0, historicalSchedules.get())
        assertEquals(
            listOf(raw.id),
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE),
        )

        val logged = harness.appLog.readAll().joinToString("\n") { it.message }
        assertTrue(logged.contains("failures="))
        assertTrue(logged.contains("retry_state="))
        assertFalse(logged.contains("Keeta"))
        assertFalse(logged.contains("conflict-existing"))
        assertFalse(logged.contains(LiveSmsProcessingHarness.PURCHASE_BODY))

        harness.nonthrowingSaveConflicts.set(0)
        assertEquals(
            ListenableWorker.Result.success(),
            worker(raw.id, processStored = harness.processStored()).doWork(),
        )
        assertEquals(1, harness.db.financialTransactionDao().count())
        assertEquals(1, harness.db.rawSmsDao().count())
        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(raw.id)!!.type)
        assertEquals(listOf(raw.id), harness.ftRepo.listRawSmsIds(harness.ftRepo.findByRawSmsId(raw.id)!!.id))
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
        assertEquals(listAllBefore, harness.reconciliationListAllCalls.get())
    }

    @Test
    fun liveThrownReconciliationFailure_isAlsoIncomplete() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms(at = "2026-08-04T09:00:00Z")
        assertTrue(harness.capture.capture(raw) is BankSmsCaptureResult.Captured)
        val processStored = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                reconciliationFailuresRemaining = AtomicInteger(2),
            ),
            appLogService = harness.appLog,
        )

        val outcome = processStored.process(raw.id)

        assertEquals(
            DerivedProcessingStage.RECONCILIATION,
            (outcome as SmsIngestionResult.DerivedIncomplete).stage,
        )
        assertNull(outcome.failureCount)
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))
        val logged = harness.appLog.readAll().joinToString("\n") { it.message }
        assertFalse(logged.contains("reconciliation unavailable"))
        assertEquals(ListenableWorker.Result.retry(), worker(raw.id, processStored = processStored).doWork())
    }

    @Test
    fun historicalPartialConflict_isIncomplete_andRecoveryClearsOnlyAfterSuccess() = runBlocking {
        val first = LiveSmsProcessingHarness.liveSms(at = "2026-08-12T10:00:00Z")
        val second = LiveSmsProcessingHarness.liveSms(at = "2026-08-12T11:00:00Z")
        val otp = LiveSmsProcessingHarness.liveSms(
            body = LiveSmsProcessingHarness.OTP_BODY,
            at = "2026-08-12T12:00:00Z",
        )
        val schedules = AtomicInteger(0)
        val batch = harness.historicalBatch(batchRecoveryScheduler = { schedules.incrementAndGet() }).startBatch()
        assertTrue(batch.ingest(first) is SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(second) is SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(otp) is SmsIngestionResult.NonFinancial)
        val listAllBefore = harness.reconciliationListAllCalls.get()
        harness.nonthrowingSaveConflicts.set(1)

        val finished = batch.finish()

        assertEquals(
            DerivedProcessingStage.RECONCILIATION,
            (finished as HistoricalBatchDerivedResult.Incomplete).stage,
        )
        assertEquals(1, schedules.get())
        assertEquals(1, harness.db.financialTransactionDao().count())
        val postedId = listOf(first.id, second.id).single { harness.ftRepo.findByRawSmsId(it) != null }
        val missingId = listOf(first.id, second.id).single { it != postedId }
        assertEquals(listOf(postedId), harness.ftRepo.listRawSmsIds(harness.ftRepo.findByRawSmsId(postedId)!!.id))
        assertNull(harness.ftRepo.findByRawSmsId(otp.id))
        assertEquals(
            listOf(first.id, second.id),
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )
        assertEquals(listAllBefore, harness.reconciliationListAllCalls.get())

        harness.nonthrowingSaveConflicts.set(Int.MAX_VALUE)
        val stalled = runCatching { harness.derivedRecovery().recoverPending() }.exceptionOrNull()
        assertTrue(stalled is ReconciliationIncompleteException)
        assertEquals(
            listOf(first.id, second.id),
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )
        assertEquals(1, harness.db.financialTransactionDao().count())

        harness.nonthrowingSaveConflicts.set(0)
        harness.derivedRecovery().recoverPending()
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
        assertEquals(2, harness.db.financialTransactionDao().count())
        assertNotNull(harness.ftRepo.findByRawSmsId(missingId))
        assertEquals(1, harness.ftRepo.listRawSmsIds(harness.ftRepo.findByRawSmsId(first.id)!!.id).size)
        assertEquals(1, harness.ftRepo.listRawSmsIds(harness.ftRepo.findByRawSmsId(second.id)!!.id).size)
        assertNull(harness.ftRepo.findByRawSmsId(otp.id))
        assertEquals(listAllBefore, harness.reconciliationListAllCalls.get())
    }

    @Test
    fun explicitResolutions_stayAuthoritativeWhenAnotherSmsConflicts() = runBlocking {
        val closed = LiveSmsProcessingHarness.liveSms(at = "2026-08-13T10:00:00Z")
        val external = LiveSmsProcessingHarness.liveSms(at = "2026-08-13T11:00:00Z")
        val paired = LiveSmsProcessingHarness.liveSms(at = "2026-08-13T12:00:00Z")
        val open = LiveSmsProcessingHarness.liveSms(at = "2026-08-13T13:00:00Z")
        val batch = harness.historicalBatch().startBatch()
        assertTrue(batch.ingest(closed) is SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(external) is SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(paired) is SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(open) is SmsIngestionResult.Parsed)
        markResolved(closed.id, ReviewResolutionKind.USER_NON_FINANCIAL)
        markResolved(external.id, ReviewResolutionKind.USER_EXTERNAL_TRANSFER)
        markResolved(paired.id, ReviewResolutionKind.USER_SELF_TRANSFER_PAIR)
        harness.nonthrowingSaveConflicts.set(Int.MAX_VALUE)

        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Incomplete)

        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, resolutionOf(closed.id))
        assertEquals(ReviewResolutionKind.USER_EXTERNAL_TRANSFER, resolutionOf(external.id))
        assertEquals(ReviewResolutionKind.USER_SELF_TRANSFER_PAIR, resolutionOf(paired.id))
        assertEquals(0, harness.db.financialTransactionDao().count())
        val retry = harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH)
        assertFalse(closed.id in retry)
        assertTrue(open.id in retry)
    }

    @Test
    fun restoreConflictWithoutAPost_rollsBackToThePreviousNonFinancialDecision() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms(at = "2026-08-14T10:00:00Z")
        val captured = harness.capture.capture(raw) as BankSmsCaptureResult.Captured
        val stored = harness.processStored().parseAndStore(captured.rawSms, captured.route)
        assertTrue(stored is SmsIngestionResult.Parsed)
        markResolved(raw.id, ReviewResolutionKind.USER_NON_FINANCIAL)
        harness.nonthrowingSaveConflicts.set(Int.MAX_VALUE)
        val restore = TransactionRestoreService(
            reviewRepository = harness.reviewRepo,
            financialTransactionRepository = harness.ledgerUnderTest,
            reconciliation = com.baraa.masroof.application.transaction.TransactionReconciliationService(
                parsedEventRepository = harness.parsedRepo,
                rawSmsRepository = harness.rawRepo,
                financialTransactionRepository = harness.ledgerUnderTest,
                ownershipResolver = com.baraa.masroof.domain.ownership.OwnershipResolver(
                    RoomAccountRegistryRepository.from(harness.db),
                    RoomCardRegistryRepository.from(harness.db),
                    NoOpLoanRegistryRepository,
                ),
                reviewRepository = harness.reviewRepo,
            ),
            reclassification = com.baraa.masroof.application.transaction.TransactionReclassificationService(
                financialTransactionRepository = harness.ftRepo,
                effectiveParsedEventProvider = com.baraa.masroof.application.review.EffectiveParsedEventProvider(
                    harness.parsedRepo,
                    com.baraa.masroof.data.repository.RoomUserCorrectionRepository(harness.db.userCorrectionDao()),
                ),
                ownershipResolver = com.baraa.masroof.domain.ownership.OwnershipResolver(
                    RoomAccountRegistryRepository.from(harness.db),
                    RoomCardRegistryRepository.from(harness.db),
                    NoOpLoanRegistryRepository,
                ),
            ),
            clock = com.baraa.masroof.sms.time.InstantClock { Instant.parse("2026-08-14T12:00:00Z") },
            reviewQueueUpdater = com.baraa.masroof.application.review.ReviewQueueUpdater(
                harness.reviewRepo,
                harness.ftRepo,
                com.baraa.masroof.sms.time.InstantClock { Instant.parse("2026-08-14T12:00:00Z") },
            ),
            processingRecovery = com.baraa.masroof.application.ingestion.ProcessingRecovery(
                processingRetryRepository = harness.processingRetryRepo,
                reviewRepository = harness.reviewRepo,
                ingestionReviewService = IngestionReviewService(
                    harness.reviewRepo,
                    com.baraa.masroof.sms.time.InstantClock { Instant.parse("2026-08-14T12:00:00Z") },
                ),
                clock = com.baraa.masroof.sms.time.InstantClock { Instant.parse("2026-08-14T12:00:00Z") },
            ),
        )

        val result = restore.restore(raw.id)

        assertTrue(result is com.baraa.masroof.application.transaction.RestoreResult.Rejected)
        assertEquals(
            "reconciliation_incomplete",
            (result as com.baraa.masroof.application.transaction.RestoreResult.Rejected).reason,
        )
        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, resolutionOf(raw.id))
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
        assertNotNull(harness.parsedRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun ownershipChange_conflict_keepsTheOwnershipDecisionAndRetriesOnlyTheAffectedSms() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms(at = "2026-08-15T10:00:00Z")
        val captured = harness.capture.capture(raw) as BankSmsCaptureResult.Captured
        val stored = harness.processStored().parseAndStore(captured.rawSms, captured.route)
        assertTrue(stored is SmsIngestionResult.Parsed)
        val cards = RoomCardRegistryRepository.from(harness.db)
        val accounts = RoomAccountRegistryRepository.from(harness.db)
        val confirmation = OwnershipConfirmationService(accounts, cards, NoOpLoanRegistryRepository)
        val card = CardReference(Bank.BANK_ALJAZIRA, "7271")
        confirmation.confirmCardOwned(card)
        harness.nonthrowingSaveConflicts.set(Int.MAX_VALUE)
        val schedules = AtomicInteger(0)
        val workflow = reviewWorkflow(confirmation, schedules)
        val listAllBefore = harness.reconciliationListAllCalls.get()

        val failure = runCatching {
            workflow.reconcileOwnershipChange(
                com.baraa.masroof.application.review.ReviewWorkflowService.OwnershipChange.Card(card),
            )
        }.exceptionOrNull()

        assertTrue(failure is ReconciliationIncompleteException)
        assertEquals(
            com.baraa.masroof.domain.model.OwnershipStatus.OWNED,
            cards.resolve(card),
        )
        assertEquals(
            listOf(raw.id),
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty())
        assertEquals(1, schedules.get())
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))
        assertEquals(listAllBefore, harness.reconciliationListAllCalls.get())
    }

    private fun worker(
        rawSmsId: String,
        attempt: Int = 0,
        processStored: ProcessStoredSmsUseCase,
    ): LiveSmsProcessingWorker =
        TestListenableWorkerBuilder<LiveSmsProcessingWorker>(context)
            .setInputData(LiveSmsProcessingWorker.inputFor(rawSmsId))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(LiveSmsProcessingWorker.Factory({ processStored }, harness.appLog))
            .build()

    private suspend fun markResolved(rawSmsId: String, kind: ReviewResolutionKind) {
        val created = harness.reviewRepo.upsertRequired(
            rawSmsId = rawSmsId,
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("seed"),
            now = Instant.parse("2026-08-13T09:00:00Z"),
        )
        harness.reviewRepo.markResolved(
            id = created.id,
            resolutionKind = kind,
            resolvedAt = Instant.parse("2026-08-13T09:05:00Z"),
            resolvedTransactionId = null,
        )
    }

    private suspend fun resolutionOf(rawSmsId: String): ReviewResolutionKind? =
        harness.reviewRepo.findByRawSmsId(rawSmsId)?.resolutionKind

    private fun reviewWorkflow(
        confirmation: OwnershipConfirmationService,
        schedules: AtomicInteger,
    ): com.baraa.masroof.application.review.ReviewWorkflowService {
        val cards = RoomCardRegistryRepository.from(harness.db)
        val accounts = RoomAccountRegistryRepository.from(harness.db)
        val resolver = com.baraa.masroof.domain.ownership.OwnershipResolver(
            accounts,
            cards,
            NoOpLoanRegistryRepository,
        )
        val clock = com.baraa.masroof.sms.time.InstantClock { Instant.parse("2026-08-15T12:00:00Z") }
        val parsed = object : com.baraa.masroof.parsing.repository.ParsedEventRepository by harness.parsedRepo {
            override suspend fun listAll(): List<com.baraa.masroof.parsing.repository.ParsedEventRecord> {
                harness.reconciliationListAllCalls.incrementAndGet()
                return harness.parsedRepo.listAll()
            }
        }
        val effective = com.baraa.masroof.application.review.EffectiveParsedEventProvider(
            parsed,
            com.baraa.masroof.data.repository.RoomUserCorrectionRepository(harness.db.userCorrectionDao()),
        )
        return com.baraa.masroof.application.review.ReviewWorkflowService(
            reviewRepository = harness.reviewRepo,
            userCorrectionRepository = com.baraa.masroof.data.repository.RoomUserCorrectionRepository(
                harness.db.userCorrectionDao(),
            ),
            financialTransactionRepository = harness.ledgerUnderTest,
            rawSmsRepository = harness.rawRepo,
            ownershipResolver = resolver,
            ownershipConfirmationService = confirmation,
            effectiveParsedEventProvider = effective,
            parsedEventRepository = parsed,
            reconciliationService = com.baraa.masroof.application.transaction.TransactionReconciliationService(
                parsedEventRepository = parsed,
                rawSmsRepository = harness.rawRepo,
                financialTransactionRepository = harness.ledgerUnderTest,
                ownershipResolver = resolver,
                effectiveParsedEventProvider = effective,
                reviewRepository = harness.reviewRepo,
            ),
            reviewQueueUpdater = com.baraa.masroof.application.review.ReviewQueueUpdater(
                harness.reviewRepo,
                harness.ftRepo,
                clock,
            ),
            manualReviewResolutionRepository = com.baraa.masroof.data.repository.RoomManualReviewResolutionRepository(
                harness.db,
                harness.ftRepo,
            ),
            clock = clock,
            processingRecovery = com.baraa.masroof.application.ingestion.ProcessingRecovery(
                processingRetryRepository = harness.processingRetryRepo,
                reviewRepository = harness.reviewRepo,
                ingestionReviewService = IngestionReviewService(harness.reviewRepo, clock),
                clock = clock,
            ),
            onHistoricalRetry = { schedules.incrementAndGet() },
        )
    }
}
