package com.baraa.masroof.application.sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every persisted recognized-bank RawSms ends as posted, non-financial, pending review,
 * or a retryable processing error once live work has finished.
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
            .setWorkerFactory(LiveSmsProcessingWorker.Factory { processStored })
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
        if (transaction != null) return TerminalOutcome.POSTED
        if (review?.status == ReviewStatus.REQUIRED &&
            review.reasons == listOf(IngestionReviewService.REASON_PROCESSING_ERROR) &&
            review.resolutionKind == null
        ) {
            return TerminalOutcome.RETRYABLE_PROCESSING_ERROR
        }
        if (review?.status == ReviewStatus.REQUIRED) return TerminalOutcome.REVIEW_PENDING
        if (parsed?.parseStatus == ParseStatus.NON_FINANCIAL) return TerminalOutcome.NON_FINANCIAL
        if (review?.status == ReviewStatus.RESOLVED &&
            review.resolutionKind == ReviewResolutionKind.USER_NON_FINANCIAL
        ) {
            return TerminalOutcome.NON_FINANCIAL
        }
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
