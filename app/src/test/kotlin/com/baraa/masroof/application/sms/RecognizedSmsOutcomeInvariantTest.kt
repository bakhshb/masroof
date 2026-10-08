package com.baraa.masroof.application.sms

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.DerivedProcessingStage
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Every persisted recognized-bank RawSms ends as posted, non-financial, pending review,
 * or retryable processing once work has finished. The same classifier covers the live
 * worker and a historical batch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecognizedSmsOutcomeInvariantTest {
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
    fun finishedLiveWork_coversEveryTerminalOutcome() = runBlocking {
        val purchase = capture(LiveSmsProcessingHarness.PURCHASE_BODY)
        val otp = capture(LiveSmsProcessingHarness.OTP_BODY)
        val collision = capture(COLLIDING_TRANSFER_BODY)
        assertEquals(ListenableWorkerResult.SUCCESS, runWorker(purchase))
        assertEquals(ListenableWorkerResult.SUCCESS, runWorker(otp))
        assertEquals(ListenableWorkerResult.SUCCESS, runWorker(collision))

        assertEquals(TerminalOutcome.POSTED, outcomeOf(purchase))
        assertEquals(TerminalOutcome.NON_FINANCIAL, outcomeOf(otp))
        assertEquals(TerminalOutcome.REVIEW_PENDING, outcomeOf(collision))

        val exhaustedId = capture(LiveSmsProcessingHarness.PURCHASE_BODY, at = "2026-08-04T09:00:00Z")
        val exhausted = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                reconciliationFailuresRemaining = AtomicInteger(Int.MAX_VALUE),
            ),
        )
        assertEquals(ListenableWorkerResult.RETRY, runWorker(exhaustedId, processStored = exhausted, attempt = 0))
        assertNull(outcomeOf(exhaustedId))
        repeat(LiveSmsProcessingWorker.MAX_ATTEMPTS - 1) { index ->
            runWorker(exhaustedId, processStored = exhausted, attempt = index + 1)
        }
        assertEquals(TerminalOutcome.RETRYABLE_PROCESSING_ERROR, outcomeOf(exhaustedId))
        assertEquals(
            listOf(exhaustedId),
            harness.reviewRepo.listRetryableProcessingErrorRawSmsIds(),
        )
    }

    @Test
    fun historicalBatchReconciliationFailure_isRetryableWithoutPerSmsDerivedWork() = runBlocking {
        val scheduled = java.util.concurrent.atomic.AtomicInteger(0)
        val batch = harness.historicalBatch(
            reconciliationFails = true,
            batchRecoveryScheduler = { scheduled.incrementAndGet() },
        ).startBatch()
        val purchase = LiveSmsProcessingHarness.liveSms()
        val otp = LiveSmsProcessingHarness.liveSms(
            body = LiveSmsProcessingHarness.OTP_BODY,
            at = "2026-08-03T15:00:00Z",
        )

        assertTrue(batch.ingest(purchase) is SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(otp) is SmsIngestionResult.NonFinancial)
        assertEquals(0, harness.db.financialTransactionDao().count())

        val finished = batch.finish()
        assertEquals(
            DerivedProcessingStage.RECONCILIATION,
            (finished as HistoricalBatchDerivedResult.Incomplete).stage,
        )
        assertNull(harness.ftRepo.findByRawSmsId(purchase.id))
        assertEquals(TerminalOutcome.RETRYABLE_PROCESSING_ERROR, outcomeOf(purchase.id))
        assertEquals(TerminalOutcome.NON_FINANCIAL, outcomeOf(otp.id))
        assertEquals(
            listOf(purchase.id),
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )
        assertTrue(
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty(),
        )
        assertEquals(1, scheduled.get())
        assertNull(harness.reviewRepo.findByRawSmsId(purchase.id))
    }

    @Test
    fun historicalBatchMarkerWriteFailure_acceptsNoPartialSet_andRetryWritesTheFullSet() = runBlocking {
        var failWrite = true
        val marked = mutableListOf<List<String>>()
        val scheduled = AtomicInteger(0)
        val retryRepository = object : ProcessingRetryRepository by harness.processingRetryRepo {
            override suspend fun markRequired(
                rawSmsIds: List<String>,
                createdAt: Instant,
                mode: ProcessingRetryMode,
            ) {
                marked += rawSmsIds
                harness.db.withTransaction {
                    harness.processingRetryRepo.markRequired(rawSmsIds, createdAt, mode)
                    if (failWrite) throw IOException("batch write failed")
                }
            }
        }
        val batch = harness.historicalBatch(
            reconciliationFails = true,
            batchRecoveryScheduler = { scheduled.incrementAndGet() },
            processingRetryRepository = retryRepository,
        ).startBatch()
        val first = LiveSmsProcessingHarness.liveSms(at = "2026-08-07T10:00:00Z")
        val second = LiveSmsProcessingHarness.liveSms(at = "2026-08-07T11:00:00Z")
        assertTrue(batch.ingest(first) is SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(second) is SmsIngestionResult.Parsed)

        val failure = runCatching { batch.finish() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0, scheduled.get())
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
        assertTrue(harness.parsedRepo.findByRawSmsId(first.id) != null)
        assertTrue(harness.parsedRepo.findByRawSmsId(second.id) != null)
        assertEquals(listOf(listOf(first.id, second.id)), marked)

        failWrite = false
        val finished = batch.finish()
        assertEquals(
            DerivedProcessingStage.RECONCILIATION,
            (finished as HistoricalBatchDerivedResult.Incomplete).stage,
        )
        assertEquals(
            listOf(first.id, second.id),
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )
        assertEquals(1, scheduled.get())
        assertEquals(listOf(first.id, second.id), marked.last())
    }

    @Test
    fun historicalReviewUpdateFailure_keepsTheWholeSetHistoricalDespitePartialReviews() = runBlocking {
        var reviewsWritten = 0
        val failingReviews = object : ReviewRepository by harness.reviewRepo {
            override suspend fun upsertRequired(
                rawSmsId: String,
                kind: ReviewKind,
                reasons: List<String>,
                now: Instant,
            ) = harness.reviewRepo.upsertRequired(rawSmsId, kind, reasons, now).also {
                reviewsWritten += 1
                if (reviewsWritten >= 1) throw IOException("review update failed")
            }
        }
        val liveScheduled = mutableListOf<String>()
        val batchScheduled = AtomicInteger(0)
        val startupScheduled = AtomicInteger(0)
        val batch = harness.historicalBatch(
            batchRecoveryScheduler = { batchScheduled.incrementAndGet() },
            reviewRepository = failingReviews,
        ).startBatch()
        val first = LiveSmsProcessingHarness.liveSms(
            body = COLLIDING_TRANSFER_BODY,
            at = "2026-08-12T10:00:00Z",
        )
        val second = LiveSmsProcessingHarness.liveSms(
            body = COLLIDING_TRANSFER_BODY,
            at = "2026-08-12T11:00:00Z",
        )
        assertTrue(batch.ingest(first) is SmsIngestionResult.ReviewRequired)
        assertTrue(batch.ingest(second) is SmsIngestionResult.ReviewRequired)

        val finished = batch.finish()
        assertEquals(
            DerivedProcessingStage.REVIEW_UPDATE,
            (finished as HistoricalBatchDerivedResult.Incomplete).stage,
        )
        val affected = listOf(first.id, second.id)
        assertEquals(
            affected,
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )
        assertTrue(
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty(),
        )
        assertEquals(1, reviewsWritten)
        val reviewed = affected.count { harness.reviewRepo.findByRawSmsId(it) != null }
        assertTrue(reviewed in 1 until affected.size)

        val intake = LiveSmsIntake(
            captureBankSms = harness.capture,
            scheduler = { liveScheduled += it },
            rawSmsRepository = harness.rawRepo,
            reviewRepository = harness.reviewRepo,
            processingRetryRepository = harness.processingRetryRepo,
            appLogService = harness.appLog,
            batchRecoveryScheduler = { startupScheduled.incrementAndGet() },
        )
        assertEquals(0, intake.schedulePendingProcessing())
        assertTrue(liveScheduled.isEmpty())
        assertEquals(1, batchScheduled.get())
        assertEquals(1, startupScheduled.get())

        harness.derivedRecovery().recoverPending()
        assertTrue(
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH).isEmpty(),
        )
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
    }

    @Test
    fun historicalRecovery_clearsEveryRetryMarkerAfterOneDerivedPass() = runBlocking {
        val first = LiveSmsProcessingHarness.liveSms(at = "2026-08-08T10:00:00Z")
        val second = LiveSmsProcessingHarness.liveSms(at = "2026-08-08T11:00:00Z")
        val batch = harness.historicalBatch(reconciliationFails = true).startBatch()
        assertTrue(batch.ingest(first) is SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(second) is SmsIngestionResult.Parsed)
        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Incomplete)

        harness.derivedRecovery().recoverPending()

        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(first.id)!!.type)
        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(second.id)!!.type)
        assertEquals(2, harness.db.financialTransactionDao().count())
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())

        harness.derivedRecovery().recoverPending()
        assertEquals(2, harness.db.financialTransactionDao().count())
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
    }

    @Test
    fun historicalRecoveryWorker_retriesWithoutDroppingTheSet() = runBlocking {
        val first = LiveSmsProcessingHarness.liveSms(at = "2026-08-09T10:00:00Z")
        val second = LiveSmsProcessingHarness.liveSms(at = "2026-08-09T11:00:00Z")
        val batch = harness.historicalBatch(reconciliationFails = true).startBatch()
        assertTrue(batch.ingest(first) is SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(second) is SmsIngestionResult.Parsed)
        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Incomplete)

        val failing = TestListenableWorkerBuilder<HistoricalDerivedRecoveryWorker>(context)
            .setWorkerFactory(
                HistoricalDerivedRecoveryWorker.Factory {
                    harness.derivedRecovery(reconciliationFails = true)
                },
            )
            .build()
        assertEquals(ListenableWorker.Result.retry(), failing.doWork())
        assertEquals(
            listOf(first.id, second.id),
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )

        val recovering = TestListenableWorkerBuilder<HistoricalDerivedRecoveryWorker>(context)
            .setWorkerFactory(HistoricalDerivedRecoveryWorker.Factory { harness.derivedRecovery() })
            .build()
        assertEquals(ListenableWorker.Result.success(), recovering.doWork())
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())

        val again = TestListenableWorkerBuilder<HistoricalDerivedRecoveryWorker>(context)
            .setWorkerFactory(HistoricalDerivedRecoveryWorker.Factory { harness.derivedRecovery() })
            .build()
        assertEquals(ListenableWorker.Result.success(), again.doWork())
        assertEquals(2, harness.db.financialTransactionDao().count())
    }

    @Test
    fun historicalBatch_skipsResolvedNonFinancial_andStartupSchedulesOneWorker() = runBlocking {
        val financial = LiveSmsProcessingHarness.liveSms(at = "2026-08-10T10:00:00Z")
        val closed = LiveSmsProcessingHarness.liveSms(at = "2026-08-10T11:00:00Z")
        val batch = harness.historicalBatch(reconciliationFails = true).startBatch()
        assertTrue(batch.ingest(financial) is SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(closed) is SmsIngestionResult.Parsed)
        val created = harness.reviewRepo.upsertRequired(
            rawSmsId = closed.id,
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("seed"),
            now = Instant.parse("2026-08-11T12:00:00Z"),
        )
        harness.reviewRepo.markResolved(
            id = created.id,
            resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL,
            resolvedAt = Instant.parse("2026-08-11T12:05:00Z"),
            resolvedTransactionId = null,
        )
        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Incomplete)
        assertEquals(listOf(financial.id), harness.processingRetryRepo.listRetryableRawSmsIds())

        val liveScheduled = mutableListOf<String>()
        val batchScheduled = AtomicInteger(0)
        val intake = LiveSmsIntake(
            captureBankSms = harness.capture,
            scheduler = { liveScheduled += it },
            rawSmsRepository = harness.rawRepo,
            reviewRepository = harness.reviewRepo,
            processingRetryRepository = harness.processingRetryRepo,
            appLogService = harness.appLog,
            batchRecoveryScheduler = { batchScheduled.incrementAndGet() },
        )
        assertEquals(0, intake.schedulePendingProcessing())
        assertTrue(liveScheduled.isEmpty())
        assertEquals(1, batchScheduled.get())
        assertEquals(TerminalOutcome.NON_FINANCIAL, outcomeOf(closed.id))
        assertEquals(TerminalOutcome.RETRYABLE_PROCESSING_ERROR, outcomeOf(financial.id))
    }

    @Test
    fun finalProcessingErrorPersistenceFailure_doesNotEndTheWorker() = runBlocking {
        val rawId = capture(LiveSmsProcessingHarness.PURCHASE_BODY, at = "2026-08-04T08:00:00Z")
        val exhausted = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                reconciliationFailuresRemaining = AtomicInteger(Int.MAX_VALUE),
                processingErrorUpsertFailuresRemaining = AtomicInteger(1),
            ),
        )
        val finalAttempt = LiveSmsProcessingWorker.MAX_ATTEMPTS - 1

        assertEquals(
            ListenableWorkerResult.RETRY,
            runWorker(rawId, processStored = exhausted, attempt = finalAttempt),
        )
        assertNull(outcomeOf(rawId))

        assertEquals(
            ListenableWorkerResult.FAILURE,
            runWorker(rawId, processStored = exhausted, attempt = finalAttempt + 1),
        )
        assertEquals(TerminalOutcome.RETRYABLE_PROCESSING_ERROR, outcomeOf(rawId))
        assertEquals(listOf(rawId), harness.reviewRepo.listRetryableProcessingErrorRawSmsIds())
        assertEquals(listOf(rawId), harness.processingRetryRepo.listRetryableRawSmsIds())
    }

    @Test
    fun resolvedFinancialReview_staysRetryableAfterExhaustedDerivedProcessing() = runBlocking {
        val kinds = listOf(
            ReviewResolutionKind.USER_FINANCIAL_TYPE,
            ReviewResolutionKind.USER_CORRECTION,
            ReviewResolutionKind.USER_EXTERNAL_TRANSFER,
            ReviewResolutionKind.USER_SELF_TRANSFER_PAIR,
        )
        val receivedAt = listOf(
            "2026-08-05T10:00:00Z",
            "2026-08-05T11:00:00Z",
            "2026-08-05T12:00:00Z",
            "2026-08-05T13:00:00Z",
        )
        val ids = kinds.mapIndexed { index, kind ->
            val rawId = capture(
                LiveSmsProcessingHarness.PURCHASE_BODY,
                at = receivedAt[index],
            )
            val created = harness.reviewRepo.upsertRequired(
                rawSmsId = rawId,
                kind = ReviewKind.NEEDS_REVIEW,
                reasons = listOf("seed"),
                now = Instant.parse("2026-08-11T12:00:00Z"),
            )
            harness.reviewRepo.markResolved(
                id = created.id,
                resolutionKind = kind,
                resolvedAt = Instant.parse("2026-08-11T12:05:00Z"),
                resolvedTransactionId = null,
            )
            val exhausted = harness.processStored(
                derivedFailures = DerivedFailureInjection(
                    reconciliationFailuresRemaining = AtomicInteger(Int.MAX_VALUE),
                ),
            )
            repeat(LiveSmsProcessingWorker.MAX_ATTEMPTS) { attempt ->
                runWorker(rawId, processStored = exhausted, attempt = attempt)
            }
            val review = harness.reviewRepo.findByRawSmsId(rawId)!!
            assertEquals(ReviewStatus.RESOLVED, review.status)
            assertEquals(kind, review.resolutionKind)
            assertEquals(TerminalOutcome.RETRYABLE_PROCESSING_ERROR, outcomeOf(rawId))
            rawId
        }

        assertEquals(ids, harness.processingRetryRepo.listRetryableRawSmsIds())
        val scheduled = mutableListOf<String>()
        assertEquals(ids.size, harness.intake { scheduled += it }.schedulePendingProcessing())
        assertEquals(ids, scheduled)
    }

    @Test
    fun resolvedNonFinancialReview_staysClosedAfterExhaustedDerivedProcessing() = runBlocking {
        val rawId = capture(LiveSmsProcessingHarness.PURCHASE_BODY, at = "2026-08-06T09:00:00Z")
        val created = harness.reviewRepo.upsertRequired(
            rawSmsId = rawId,
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf(IngestionReviewService.REASON_PROCESSING_ERROR),
            now = Instant.parse("2026-08-11T12:00:00Z"),
        )
        harness.reviewRepo.markResolved(
            id = created.id,
            resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL,
            resolvedAt = Instant.parse("2026-08-11T12:05:00Z"),
            resolvedTransactionId = null,
        )
        val exhausted = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                reconciliationFailuresRemaining = AtomicInteger(Int.MAX_VALUE),
            ),
        )
        repeat(LiveSmsProcessingWorker.MAX_ATTEMPTS - 1) { attempt ->
            assertEquals(
                ListenableWorkerResult.RETRY,
                runWorker(rawId, processStored = exhausted, attempt = attempt),
            )
            assertEquals(TerminalOutcome.NON_FINANCIAL, outcomeOf(rawId))
        }
        assertEquals(
            ListenableWorkerResult.FAILURE,
            runWorker(
                rawId,
                processStored = exhausted,
                attempt = LiveSmsProcessingWorker.MAX_ATTEMPTS - 1,
            ),
        )

        val review = harness.reviewRepo.findByRawSmsId(rawId)!!
        assertEquals(ReviewStatus.RESOLVED, review.status)
        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, review.resolutionKind)
        assertEquals(TerminalOutcome.NON_FINANCIAL, outcomeOf(rawId))
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
        assertEquals(0, harness.intake { }.schedulePendingProcessing())
    }

    @Test
    fun unrecognizedSender_isNotPersisted() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms(body = "hello", at = "2026-08-03T08:00:00Z")
            .copy(sender = "OtherBank")
        assertTrue(harness.capture.capture(raw) is BankSmsCaptureResult.NotRelevant)
        assertEquals(0, harness.db.rawSmsDao().count())
    }

    private suspend fun capture(body: String, at: String = "2026-08-03T14:32:00Z"): String {
        val raw = LiveSmsProcessingHarness.liveSms(body = body, at = at)
        assertTrue(harness.capture.capture(raw) is BankSmsCaptureResult.Captured)
        return raw.id
    }

    private suspend fun runWorker(
        rawSmsId: String,
        processStored: ProcessStoredSmsUseCase = harness.processStored(),
        attempt: Int = 0,
    ): ListenableWorkerResult {
        val result = TestListenableWorkerBuilder<LiveSmsProcessingWorker>(context)
            .setInputData(LiveSmsProcessingWorker.inputFor(rawSmsId))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(LiveSmsProcessingWorker.Factory({ processStored }))
            .build()
            .doWork()
        return when (result) {
            ListenableWorker.Result.success() -> ListenableWorkerResult.SUCCESS
            ListenableWorker.Result.retry() -> ListenableWorkerResult.RETRY
            else -> ListenableWorkerResult.FAILURE
        }
    }

    private suspend fun outcomeOf(rawSmsId: String): TerminalOutcome? {
        val transaction = harness.ftRepo.findByRawSmsId(rawSmsId)
        val review = harness.reviewRepo.findByRawSmsId(rawSmsId)
        val parsed = harness.parsedRepo.findByRawSmsId(rawSmsId)?.event
        val retryable = rawSmsId in harness.processingRetryRepo.listRetryableRawSmsIds()
        if (transaction != null) return TerminalOutcome.POSTED
        if (parsed?.parseStatus == ParseStatus.NON_FINANCIAL) return TerminalOutcome.NON_FINANCIAL
        if (review?.status == ReviewStatus.RESOLVED &&
            review.resolutionKind == ReviewResolutionKind.USER_NON_FINANCIAL
        ) {
            return TerminalOutcome.NON_FINANCIAL
        }
        if (retryable || (
                review?.status == ReviewStatus.REQUIRED &&
                    review.reasons == listOf(IngestionReviewService.REASON_PROCESSING_ERROR) &&
                    review.resolutionKind == null
                )
        ) {
            return TerminalOutcome.RETRYABLE_PROCESSING_ERROR
        }
        if (review?.status == ReviewStatus.REQUIRED) return TerminalOutcome.REVIEW_PENDING
        return null
    }

    private enum class TerminalOutcome {
        POSTED,
        NON_FINANCIAL,
        REVIEW_PENDING,
        RETRYABLE_PROCESSING_ERROR,
    }

    private enum class ListenableWorkerResult { SUCCESS, RETRY, FAILURE }

    private companion object {
        val COLLIDING_TRANSFER_BODY = """
            حوالة واردة
            حوالة صادرة
            مبلغ: SAR 50.00
            في: 2026-08-05 11:00
        """.trimIndent()
    }
}
