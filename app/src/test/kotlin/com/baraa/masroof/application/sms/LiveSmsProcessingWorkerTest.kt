package com.baraa.masroof.application.sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.RawSms
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
