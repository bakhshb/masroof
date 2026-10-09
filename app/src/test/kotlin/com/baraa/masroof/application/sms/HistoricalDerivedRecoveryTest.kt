package com.baraa.masroof.application.sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.ReconciliationIncompleteException
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoricalDerivedRecoveryTest {
    private lateinit var harness: LiveSmsProcessingHarness

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        harness = LiveSmsProcessingHarness(context)
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun nonthrowingFailure_keepsTheRetrySet_untilALaterPassClearsIt() = runBlocking {
        val first = LiveSmsProcessingHarness.liveSms(at = "2026-08-08T10:00:00Z")
        val second = LiveSmsProcessingHarness.liveSms(at = "2026-08-08T11:00:00Z")
        val batch = harness.historicalBatch().startBatch()
        assertTrue(batch.ingest(first) is com.baraa.masroof.application.ingestion.SmsIngestionResult.Parsed)
        assertTrue(batch.ingest(second) is com.baraa.masroof.application.ingestion.SmsIngestionResult.Parsed)
        harness.nonthrowingSaveConflicts.set(Int.MAX_VALUE)

        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Incomplete)
        val ids = listOf(first.id, second.id)
        assertEquals(ids, harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH))
        assertEquals(0, harness.db.financialTransactionDao().count())

        val stalled = runCatching { harness.derivedRecovery().recoverPending() }.exceptionOrNull()
        assertTrue(stalled is ReconciliationIncompleteException)
        assertTrue((stalled as ReconciliationIncompleteException).failureCount > 0)
        assertEquals(ids, harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH))
        assertEquals(0, harness.db.financialTransactionDao().count())

        harness.nonthrowingSaveConflicts.set(0)
        harness.derivedRecovery().recoverPending()

        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
        assertEquals(2, harness.db.financialTransactionDao().count())
        assertEquals(listOf(first.id), harness.ftRepo.listRawSmsIds(harness.ftRepo.findByRawSmsId(first.id)!!.id))
        assertEquals(listOf(second.id), harness.ftRepo.listRawSmsIds(harness.ftRepo.findByRawSmsId(second.id)!!.id))

        harness.derivedRecovery().recoverPending()
        assertEquals(2, harness.db.financialTransactionDao().count())
    }

    @Test
    fun reviewUpdateFailure_leavesTheRetrySetInPlace() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms()
        val captured = harness.capture.capture(raw) as BankSmsCaptureResult.Captured
        val stored = harness.processStored().parseAndStore(captured.rawSms, captured.route)
        assertTrue(stored is com.baraa.masroof.application.ingestion.SmsIngestionResult.Parsed)
        harness.processingRetryRepo.markRequired(
            raw.id,
            Instant.parse("2026-08-11T12:00:00Z"),
            ProcessingRetryMode.HISTORICAL_BATCH,
        )
        harness.reviewRepo.upsertRequired(
            rawSmsId = raw.id,
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("seed"),
            now = Instant.parse("2026-08-11T12:00:00Z"),
        )
        val failingReviews = object : ReviewRepository by harness.reviewRepo {
            override suspend fun markResolved(
                id: String,
                resolutionKind: ReviewResolutionKind,
                resolvedAt: Instant,
                resolvedTransactionId: String?,
            ): ReviewItem? = throw IOException("review update failed")
        }
        val clock = InstantClock { Instant.parse("2026-08-11T12:00:00Z") }
        val recovery = HistoricalDerivedRecovery(
            parsedEventRepository = harness.parsedRepo,
            processingRetryRepository = harness.processingRetryRepo,
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = harness.parsedRepo,
                rawSmsRepository = harness.rawRepo,
                financialTransactionRepository = harness.ftRepo,
                ownershipResolver = OwnershipResolver(
                    RoomAccountRegistryRepository.from(harness.db),
                    RoomCardRegistryRepository.from(harness.db),
                    NoOpLoanRegistryRepository,
                ),
            ),
            reviewQueueUpdater = ReviewQueueUpdater(failingReviews, harness.ftRepo, clock),
            rawSmsRepository = harness.rawRepo,
        )

        val failure = runCatching { recovery.recoverPending() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(
            listOf(raw.id),
            harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
        )
    }
}
