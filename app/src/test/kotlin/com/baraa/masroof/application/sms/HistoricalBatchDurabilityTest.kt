package com.baraa.masroof.application.sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.sms.datasource.InboxRow
import com.baraa.masroof.sms.datasource.SmsDataSource
import com.baraa.masroof.sms.model.ProviderSmsRecord
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoricalBatchDurabilityTest {
    @Test
    fun abandoningBatchAfterParse_leavesDurableBatchRecoveryIntent() = runBlocking {
        LiveSmsProcessingHarness(context()).use { harness ->
            val raw = LiveSmsProcessingHarness.liveSms()
            val batch = harness.historicalBatch().startBatch()
            assertTrue(batch.ingest(raw) is SmsIngestionResult.Parsed)
            assertNotNull(harness.parsedRepo.findByRawSmsId(raw.id))
            assertNull(harness.ftRepo.findByRawSmsId(raw.id))
            assertEquals(listOf(raw.id), harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH))
            // A new recovery object has no access to the abandoned Batch's in-memory list.
            harness.derivedRecovery().recoverPending()
            assertNotNull(harness.ftRepo.findByRawSmsId(raw.id))
            assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
            harness.derivedRecovery().recoverPending()
            assertEquals(1, harness.db.financialTransactionDao().count())
        }
    }

    @Test
    fun retryIntentWriteFailure_rollsBackTheNewParseEvidence() = runBlocking {
        LiveSmsProcessingHarness(context()).use { harness ->
            val raw = LiveSmsProcessingHarness.liveSms()
            val captured = harness.capture.capture(raw) as BankSmsCaptureResult.Captured
            harness.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_batch_intent BEFORE INSERT ON processing_retry BEGIN SELECT RAISE(ABORT, 'injected'); END",
            )
            val result = harness.processStored().parseAndStore(captured.rawSms, captured.route)
            assertTrue(result is SmsIngestionResult.Failed)
            assertNotNull(harness.rawRepo.getById(raw.id))
            assertNull(harness.parsedRepo.findByRawSmsId(raw.id))
            assertTrue(harness.processingRetryRepo.listRetryableRawSmsIds().isEmpty())
        }
    }

    @Test
    fun scannerReportsIncompleteDerivedWorkInsteadOfSuccessfulParsing() = runBlocking {
        LiveSmsProcessingHarness(context()).use { harness ->
            val raw = LiveSmsProcessingHarness.liveSms()
            val source = object : SmsDataSource {
                override fun queryInbox(receivedAfter: Instant?) = sequenceOf(
                    InboxRow.Valid(ProviderSmsRecord("history-1", raw.sender, raw.body, raw.receivedAt)),
                )
            }
            val result = HistoricalSmsScanner(source, harness.historicalBatch(reconciliationFails = true)).scan()
            assertEquals(1, result.parsed)
            assertTrue(result.failure is SmsScanFailure.DerivedIncomplete)
            assertEquals(1, harness.processingRetryRepo.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH).size)
        }
    }

    @Test
    fun providerQueryAndLazyIteration_useInjectedBackgroundDispatcher() = runBlocking {
        LiveSmsProcessingHarness(context()).use { harness ->
            val caller = Thread.currentThread()
            val queryThread = AtomicReference<Thread>()
            val iteratorThread = AtomicReference<Thread>()
            val source = object : SmsDataSource {
                override fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> {
                    queryThread.set(Thread.currentThread())
                    return sequence { iteratorThread.set(Thread.currentThread()) }
                }
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
                HistoricalSmsScanner(source, harness.historicalBatch(), ioDispatcher = io).scan()
            }
            assertNotEquals(caller, queryThread.get())
            assertEquals(queryThread.get(), iteratorThread.get())
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
