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
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.RawSmsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveSmsProcessingWorkerTest {
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
    fun capturedEvidence_isProcessedByWorker() = runBlocking {
        val raw = captured()

        val result = worker(raw.id).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(ParseStatus.SUCCESS, harness.parsedRepo.findByRawSmsId(raw.id)!!.event.parseStatus)
        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(raw.id)!!.type)
    }

    @Test
    fun nonFinancialEvidence_succeedsWithoutTransaction() = runBlocking {
        val raw = captured(LiveSmsProcessingHarness.liveSms(body = LiveSmsProcessingHarness.OTP_BODY))

        assertEquals(ListenableWorker.Result.success(), worker(raw.id).doWork())
        assertEquals(ParseStatus.NON_FINANCIAL, harness.parsedRepo.findByRawSmsId(raw.id)!!.event.parseStatus)
        assertEquals(0, harness.db.financialTransactionDao().count())
    }

    @Test
    fun missingInput_failsWithoutProcessing() = runBlocking {
        val worker = TestListenableWorkerBuilder<LiveSmsProcessingWorker>(context)
            .setWorkerFactory(LiveSmsProcessingWorker.Factory { harness.processStored() })
            .build()

        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
        assertEquals(0, harness.parseCalls.get())
    }

    @Test
    fun missingRawSms_failsPermanentlyWithoutSideEffects() = runBlocking {
        assertEquals(ListenableWorker.Result.failure(), worker("android-sms:missing").doWork())
        assertEquals(0, harness.parseCalls.get())
        assertTrue(harness.reviewRepo.listAll().isEmpty())
    }

    @Test
    fun processingFailure_retriesThenSucceeds_withoutDuplicatingEvidence() = runBlocking {
        val raw = captured()
        harness.parserFailuresRemaining.set(1)

        val first = worker(raw.id, attempt = 0).doWork()
        val retried = worker(raw.id, attempt = 1).doWork()

        assertEquals(ListenableWorker.Result.retry(), first)
        assertEquals(ListenableWorker.Result.success(), retried)
        assertEquals(1, harness.db.rawSmsDao().count())
        assertEquals(1, harness.db.parsedEventDao().count())
        assertEquals(1, harness.db.financialTransactionDao().count())
        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(raw.id)!!.type)
    }

    @Test
    fun repositoryException_isRetried() = runBlocking {
        val raw = captured()
        val failingOnce = FailingGetByIdRepository(harness.rawRepo, failures = 1)
        val processStored = harness.processStored(failingOnce)

        assertEquals(ListenableWorker.Result.retry(), worker(raw.id, processStored = processStored).doWork())
        assertEquals(ListenableWorker.Result.success(), worker(raw.id, attempt = 1, processStored = processStored).doWork())
        assertNotNull(harness.ftRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun persistentFailure_givesUpAtMaxAttempts_andKeepsReviewableEvidence() = runBlocking {
        val raw = captured()
        harness.parserFailuresRemaining.set(Int.MAX_VALUE)

        val results = (0 until LiveSmsProcessingWorker.MAX_ATTEMPTS).map { attempt ->
            worker(raw.id, attempt = attempt).doWork()
        }

        assertEquals(
            List(LiveSmsProcessingWorker.MAX_ATTEMPTS - 1) { ListenableWorker.Result.retry() } +
                ListenableWorker.Result.failure(),
            results,
        )
        assertEquals(1, harness.db.rawSmsDao().count())
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))
        val review = harness.reviewRepo.listAll().single { it.rawSmsId == raw.id }
        assertEquals(listOf(IngestionReviewService.REASON_PROCESSING_ERROR), review.reasons)
    }

    @Test
    fun cancellation_propagates_keepsEvidence_andRerunSucceeds() = runBlocking {
        val raw = captured()
        val entered = CompletableDeferred<Unit>()
        val blocking = BlockingGetByIdRepository(harness.rawRepo, entered)

        val running = async { worker(raw.id, processStored = harness.processStored(blocking)).doWork() }
        entered.await()
        running.cancel()

        val cancelled = runCatching { running.await() }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertTrue(harness.rawRepo.existsById(raw.id))
        assertNull(harness.parsedRepo.findByRawSmsId(raw.id))
        assertTrue(harness.reviewRepo.listAll().isEmpty())
        assertEquals(listOf(raw.id), harness.rawRepo.listIdsAwaitingProcessing())

        assertEquals(ListenableWorker.Result.success(), worker(raw.id, attempt = 1).doWork())
        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(raw.id)!!.type)
    }

    @Test
    fun duplicateBroadcasts_scheduleOnce_andRepeatedWorkCreatesOneTransaction() = runBlocking {
        val scheduled = mutableListOf<String>()
        val intake = harness.intake { scheduled += it }
        val raw = LiveSmsProcessingHarness.liveSms()

        assertTrue(intake.ingest(raw) is BankSmsCaptureResult.Captured)
        assertEquals(BankSmsCaptureResult.Duplicate, intake.ingest(raw))
        assertEquals(listOf(raw.id), scheduled)

        repeat(2) { attempt ->
            assertEquals(ListenableWorker.Result.success(), worker(raw.id, attempt = attempt).doWork())
        }

        assertEquals(1, harness.db.rawSmsDao().count())
        assertEquals(1, harness.db.financialTransactionDao().count())
    }

    @Test
    fun ownershipDiscoveryFailure_retriesThenPostsOneTransaction() = runBlocking {
        val raw = captured()
        val injection = DerivedFailureInjection(ownershipFailuresRemaining = AtomicInteger(1))
        val processStored = harness.processStored(derivedFailures = injection)

        val outcome = processStored.process(raw.id)
        assertEquals(
            DerivedProcessingStage.OWNERSHIP_DISCOVERY,
            (outcome as SmsIngestionResult.DerivedIncomplete).stage,
        )
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))

        assertEquals(ListenableWorker.Result.success(), worker(raw.id, processStored = processStored).doWork())
        assertEquals(1, harness.db.financialTransactionDao().count())
        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(raw.id)!!.type)
    }

    @Test
    fun reconciliationFailure_isRetriedByWorker_thenPostsOneTransaction() = runBlocking {
        val raw = captured()
        val injection = DerivedFailureInjection(reconciliationFailuresRemaining = AtomicInteger(1))
        val processStored = harness.processStored(derivedFailures = injection)

        assertEquals(ListenableWorker.Result.retry(), worker(raw.id, processStored = processStored).doWork())
        assertNotNull(harness.parsedRepo.findByRawSmsId(raw.id))
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))

        assertEquals(
            ListenableWorker.Result.success(),
            worker(raw.id, attempt = 1, processStored = processStored).doWork(),
        )
        assertEquals(1, harness.db.financialTransactionDao().count())
        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(raw.id)!!.type)
    }

    @Test
    fun persistentReconciliationFailure_givesUpWithoutPosting() = runBlocking {
        val raw = captured()
        val processStored = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                reconciliationFailuresRemaining = AtomicInteger(Int.MAX_VALUE),
            ),
        )

        assertEquals(
            ListenableWorker.Result.retry(),
            worker(raw.id, attempt = 0, processStored = processStored).doWork(),
        )
        assertTrue(harness.reviewRepo.listAll().isEmpty())

        val results = listOf(ListenableWorker.Result.retry()) +
            (1 until LiveSmsProcessingWorker.MAX_ATTEMPTS).map { attempt ->
                worker(raw.id, attempt = attempt, processStored = processStored).doWork()
            }

        assertEquals(
            List(LiveSmsProcessingWorker.MAX_ATTEMPTS - 1) { ListenableWorker.Result.retry() } +
                ListenableWorker.Result.failure(),
            results,
        )
        assertNotNull(harness.parsedRepo.findByRawSmsId(raw.id))
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))
        val review = harness.reviewRepo.findByRawSmsId(raw.id)!!
        assertEquals(ReviewStatus.REQUIRED, review.status)
        assertEquals(listOf(IngestionReviewService.REASON_PROCESSING_ERROR), review.reasons)
        assertNull(review.resolutionKind)

        val scheduled = mutableListOf<String>()
        assertEquals(1, harness.intake { scheduled += it }.schedulePendingProcessing())
        assertEquals(listOf(raw.id), scheduled)
        assertEquals(listOf(raw.id), harness.processingRetryRepo.listRetryableRawSmsIds())
    }

    @Test
    fun exhaustedDerivedFailure_isRecoveredByStartup_andResolvesTheProcessingError() = runBlocking {
        val raw = captured()
        val exhausted = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                reconciliationFailuresRemaining = AtomicInteger(Int.MAX_VALUE),
            ),
        )
        repeat(LiveSmsProcessingWorker.MAX_ATTEMPTS) { attempt ->
            worker(raw.id, attempt = attempt, processStored = exhausted).doWork()
        }
        assertEquals(ReviewStatus.REQUIRED, harness.reviewRepo.findByRawSmsId(raw.id)!!.status)

        assertEquals(
            ListenableWorker.Result.success(),
            worker(raw.id, processStored = harness.processStored()).doWork(),
        )
        val review = harness.reviewRepo.findByRawSmsId(raw.id)!!
        assertEquals(ReviewStatus.RESOLVED, review.status)
        assertEquals(ReviewResolutionKind.AUTO_NO_LONGER_REQUIRED, review.resolutionKind)
        assertEquals(1, harness.db.financialTransactionDao().count())
        assertEquals(0, harness.intake { }.schedulePendingProcessing())
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
    }

    @Test
    fun exhaustedDerivedProcessing_doesNotReopenAResolvedUserReview() = runBlocking {
        val raw = captured()
        val created = harness.reviewRepo.upsertRequired(
            rawSmsId = raw.id,
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

        harness.processStored().recordExhaustedDerivedProcessing(raw.id)

        val stored = harness.reviewRepo.findByRawSmsId(raw.id)!!
        assertEquals(ReviewStatus.RESOLVED, stored.status)
        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, stored.resolutionKind)
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
        assertEquals(0, harness.intake { }.schedulePendingProcessing())
    }

    @Test
    fun finalAttempt_retriesWhenProcessingErrorCannotBePersisted() = runBlocking {
        val raw = captured()
        val processStored = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                reconciliationFailuresRemaining = AtomicInteger(Int.MAX_VALUE),
                processingErrorUpsertFailuresRemaining = AtomicInteger(1),
            ),
        )
        val finalAttempt = LiveSmsProcessingWorker.MAX_ATTEMPTS - 1

        assertEquals(
            ListenableWorker.Result.retry(),
            worker(raw.id, attempt = finalAttempt, processStored = processStored).doWork(),
        )
        assertNull(harness.reviewRepo.findByRawSmsId(raw.id))
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())

        assertEquals(
            ListenableWorker.Result.failure(),
            worker(raw.id, attempt = finalAttempt + 1, processStored = processStored).doWork(),
        )
        val review = harness.reviewRepo.findByRawSmsId(raw.id)!!
        assertEquals(ReviewStatus.REQUIRED, review.status)
        assertEquals(listOf(IngestionReviewService.REASON_PROCESSING_ERROR), review.reasons)
        assertEquals(listOf(raw.id), harness.processingRetryRepo.listRetryableRawSmsIds())
    }

    @Test
    fun reviewPersistenceFailure_retriesInsteadOfPermanentSuccess() = runBlocking {
        val raw = captured()
        harness.parseOverride = { ParseResult.Unsupported("unsupported_test") }
        val processStored = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                processingErrorUpsertFailuresRemaining = AtomicInteger(1),
            ),
        )

        assertEquals(
            ListenableWorker.Result.retry(),
            worker(raw.id, processStored = processStored).doWork(),
        )
        assertNotNull(harness.rawRepo.getById(raw.id))
        assertNull(harness.reviewRepo.findByRawSmsId(raw.id))
        assertNull(harness.parsedRepo.findByRawSmsId(raw.id))

        assertEquals(
            ListenableWorker.Result.success(),
            worker(raw.id, attempt = 1, processStored = processStored).doWork(),
        )
        val review = harness.reviewRepo.findByRawSmsId(raw.id)!!
        assertEquals(ReviewStatus.REQUIRED, review.status)
        assertEquals(listOf(IngestionReviewService.REASON_UNSUPPORTED_FORMAT), review.reasons)
        assertNotNull(harness.rawRepo.getById(raw.id))
    }

    @Test
    fun persistentUnsupportedReviewFailure_keepsRetryingPastMaxAttempts() = runBlocking {
        assertReviewStorageFailureStaysOpen { ParseResult.Unsupported("unsupported_test") }
    }

    @Test
    fun persistentNoEventReviewFailure_keepsRetryingPastMaxAttempts() = runBlocking {
        assertReviewStorageFailureStaysOpen {
            ParseResult.ReviewRequired(
                draft = null,
                event = null,
                findings = emptyList(),
                reasons = listOf("needs_review"),
            )
        }
    }

    @Test
    fun persistentProcessingErrorReviewFailure_keepsRetryingPastMaxAttempts() = runBlocking {
        val raw = captured()
        harness.parserFailuresRemaining.set(Int.MAX_VALUE)
        val processStored = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                processingErrorUpsertFailuresRemaining = AtomicInteger(Int.MAX_VALUE),
            ),
        )

        val results = (0..LiveSmsProcessingWorker.MAX_ATTEMPTS).map { attempt ->
            worker(raw.id, attempt = attempt, processStored = processStored).doWork()
        }

        assertEquals(List(LiveSmsProcessingWorker.MAX_ATTEMPTS + 1) { ListenableWorker.Result.retry() }, results)
        assertNotNull(harness.rawRepo.getById(raw.id))
        assertNull(harness.parsedRepo.findByRawSmsId(raw.id))
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))
        assertNull(harness.reviewRepo.findByRawSmsId(raw.id))
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
        assertEquals(listOf(raw.id), harness.rawRepo.listIdsAwaitingProcessing())
    }

    @Test
    fun reviewUpdateFailure_retriesWithoutLosingThePostedTransaction() = runBlocking {
        val raw = captured()
        harness.reviewRepo.upsertRequired(
            rawSmsId = raw.id,
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("seed"),
            now = Instant.parse("2026-08-11T12:00:00Z"),
        )
        val injection = DerivedFailureInjection(reviewUpdateFailuresRemaining = AtomicInteger(1))
        val processStored = harness.processStored(derivedFailures = injection)

        val outcome = processStored.process(raw.id)
        assertEquals(
            DerivedProcessingStage.REVIEW_UPDATE,
            (outcome as SmsIngestionResult.DerivedIncomplete).stage,
        )
        val posted = harness.ftRepo.findByRawSmsId(raw.id)
        assertNotNull(posted)

        assertEquals(ListenableWorker.Result.success(), worker(raw.id, processStored = processStored).doWork())
        assertEquals(posted!!.id, harness.ftRepo.findByRawSmsId(raw.id)!!.id)
        assertEquals(1, harness.db.financialTransactionDao().count())
        assertEquals(ReviewStatus.RESOLVED, harness.reviewRepo.findByRawSmsId(raw.id)!!.status)
    }

    @Test
    fun exchangeRateEnrichmentFailure_succeedsAndStillPosts() = runBlocking {
        val raw = captured()
        val processStored = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                exchangeRateFailuresRemaining = AtomicInteger(5),
            ),
        )

        val outcome = processStored.process(raw.id)
        assertTrue(outcome is SmsIngestionResult.Parsed)
        assertEquals(ListenableWorker.Result.success(), worker(raw.id, processStored = processStored).doWork())
        assertEquals(1, harness.db.financialTransactionDao().count())
        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(raw.id)!!.type)
    }

    @Test
    fun reconciliationCancellation_propagatesWithoutPosting() = runBlocking {
        val raw = captured()
        val processStored = harness.processStored(
            derivedFailures = DerivedFailureInjection(reconciliationCancels = true),
        )

        val cancelled = runCatching { worker(raw.id, processStored = processStored).doWork() }.exceptionOrNull()

        assertTrue(cancelled is CancellationException)
        assertNotNull(harness.parsedRepo.findByRawSmsId(raw.id))
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))
    }

    @Test
    fun processDeathAfterCapture_isRecoveredBySweep() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms()
        val lostBeforeScheduling = harness.intake { throw IllegalStateException("process died") }
        assertTrue(lostBeforeScheduling.ingest(raw) is BankSmsCaptureResult.Captured)
        assertEquals(0, harness.parseCalls.get())

        val rescheduled = mutableListOf<String>()
        val restarted = harness.intake { rescheduled += it }
        assertEquals(1, restarted.schedulePendingProcessing())
        assertEquals(listOf(raw.id), rescheduled)

        assertEquals(ListenableWorker.Result.success(), worker(raw.id).doWork())
        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(raw.id)!!.type)
        assertEquals(0, restarted.schedulePendingProcessing())
    }

    private suspend fun assertReviewStorageFailureStaysOpen(
        parseOverride: (com.baraa.masroof.parsing.model.SmsParseInput) -> ParseResult,
    ) {
        val raw = captured()
        harness.parseOverride = parseOverride
        val processStored = harness.processStored(
            derivedFailures = DerivedFailureInjection(
                processingErrorUpsertFailuresRemaining = AtomicInteger(Int.MAX_VALUE),
            ),
        )

        val results = (0..LiveSmsProcessingWorker.MAX_ATTEMPTS).map { attempt ->
            worker(raw.id, attempt = attempt, processStored = processStored).doWork()
        }

        assertEquals(List(LiveSmsProcessingWorker.MAX_ATTEMPTS + 1) { ListenableWorker.Result.retry() }, results)
        assertNotNull(harness.rawRepo.getById(raw.id))
        assertNull(harness.parsedRepo.findByRawSmsId(raw.id))
        assertNull(harness.ftRepo.findByRawSmsId(raw.id))
        assertNull(harness.reviewRepo.findByRawSmsId(raw.id))
        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
        assertEquals(listOf(raw.id), harness.rawRepo.listIdsAwaitingProcessing())
    }

    private suspend fun captured(raw: RawSms = LiveSmsProcessingHarness.liveSms()): RawSms {
        assertTrue(harness.capture.capture(raw) is BankSmsCaptureResult.Captured)
        return raw
    }

    private fun worker(
        rawSmsId: String,
        attempt: Int = 0,
        processStored: ProcessStoredSmsUseCase = harness.processStored(),
    ): LiveSmsProcessingWorker =
        TestListenableWorkerBuilder<LiveSmsProcessingWorker>(context)
            .setInputData(LiveSmsProcessingWorker.inputFor(rawSmsId))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(LiveSmsProcessingWorker.Factory { processStored })
            .build()

    private class FailingGetByIdRepository(
        private val delegate: RawSmsRepository,
        failures: Int,
    ) : RawSmsRepository by delegate {
        private val remaining = AtomicInteger(failures)

        override suspend fun getById(id: String): RawSms? {
            if (remaining.getAndDecrement() > 0) throw IOException("disk unavailable")
            return delegate.getById(id)
        }
    }

    private class BlockingGetByIdRepository(
        private val delegate: RawSmsRepository,
        private val entered: CompletableDeferred<Unit>,
    ) : RawSmsRepository by delegate {
        override suspend fun getById(id: String): RawSms? {
            entered.complete(Unit)
            awaitCancellation()
        }
    }
}
