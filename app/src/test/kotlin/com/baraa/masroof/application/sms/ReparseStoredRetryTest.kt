package com.baraa.masroof.application.sms

import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.domain.model.ProcessingRetryMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** A manual reparse has no worker behind it, so an incomplete pass must leave a durable retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReparseStoredRetryTest {
    private lateinit var harness: LiveSmsProcessingHarness

    @Before
    fun setUp() {
        harness = LiveSmsProcessingHarness(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = harness.close()

    @Test
    fun incompleteReconciliation_recordsAHistoricalRetry() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms()
        assertTrue(harness.capture.capture(raw) is BankSmsCaptureResult.Captured)
        val processStored = harness.processStored(
            derivedFailures = DerivedFailureInjection(reconciliationFailuresRemaining = AtomicInteger(1)),
        )

        val result = processStored.reparseStored(raw)

        assertTrue(result is SmsIngestionResult.DerivedIncomplete)
        assertEquals(listOf(raw.id), harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH))
    }

    @Test
    fun completeReparse_recordsNoRetry() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms()
        assertTrue(harness.capture.capture(raw) is BankSmsCaptureResult.Captured)

        harness.processStored().reparseStored(raw)

        assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH).isEmpty())
    }
}
