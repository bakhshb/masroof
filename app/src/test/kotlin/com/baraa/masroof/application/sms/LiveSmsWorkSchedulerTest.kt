package com.baraa.masroof.application.sms

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.domain.model.FinancialTransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveSmsWorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var harness: LiveSmsProcessingHarness
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerLiveSmsWorkScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        harness = LiveSmsProcessingHarness(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(LiveSmsProcessingWorker.Factory({ harness.processStored() }, harness.appLog))
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerLiveSmsWorkScheduler { workManager }
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        harness.close()
    }

    @Test
    fun workInput_containsOnlyRawSmsId_neverBodyOrOtp() {
        val raw = LiveSmsProcessingHarness.liveSms(body = LiveSmsProcessingHarness.OTP_BODY)

        val request = WorkManagerLiveSmsWorkScheduler.workRequest(raw.id)
        val input = request.workSpec.input

        assertEquals(setOf(LiveSmsProcessingWorker.KEY_RAW_SMS_ID), input.keyValueMap.keys)
        assertEquals(raw.id, input.getString(LiveSmsProcessingWorker.KEY_RAW_SMS_ID))
        val persisted = input.keyValueMap.values.joinToString() + request.tags.joinToString()
        assertFalse(persisted.contains(raw.body))
        assertFalse(persisted.contains(LiveSmsProcessingHarness.OTP_CODE))
        assertFalse(WorkManagerLiveSmsWorkScheduler.uniqueWorkName(raw.id).contains(LiveSmsProcessingHarness.OTP_CODE))
    }

    @Test
    fun workRequest_usesExponentialBackoff() {
        val spec = WorkManagerLiveSmsWorkScheduler.workRequest("android-sms:1").workSpec

        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(
            WorkManagerLiveSmsWorkScheduler.BACKOFF_DELAY_SECONDS * 1_000,
            spec.backoffDelayDuration,
        )
    }

    @Test
    fun scheduledCapture_runsThroughWorkManager_toTransaction() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms()
        val intake = harness.intake(scheduler)

        assertTrue(intake.ingest(raw) is BankSmsCaptureResult.Captured)

        val info = finishedWork(raw.id).single()
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertTrue(WorkManagerLiveSmsWorkScheduler.WORK_TAG in info.tags)
        assertEquals(FinancialTransactionType.EXPENSE, harness.ftRepo.findByRawSmsId(raw.id)!!.type)
    }

    @Test
    fun pendingDuplicateSchedule_isKept_asSingleWork() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms()
        assertTrue(harness.capture.capture(raw) is BankSmsCaptureResult.Captured)
        val pending = OneTimeWorkRequestBuilder<LiveSmsProcessingWorker>()
            .setInputData(LiveSmsProcessingWorker.inputFor(raw.id))
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        workManager.enqueueUniqueWork(
            WorkManagerLiveSmsWorkScheduler.uniqueWorkName(raw.id),
            ExistingWorkPolicy.KEEP,
            pending,
        ).result.get()

        scheduler.schedule(raw.id)
        scheduler.schedule(raw.id)

        val infos = uniqueWork(raw.id)
        assertEquals(listOf(pending.id), infos.map { it.id })
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(0, harness.db.financialTransactionDao().count())

        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(pending.id)

        assertEquals(WorkInfo.State.SUCCEEDED, finishedWork(raw.id).single().state)
        assertEquals(1, harness.db.financialTransactionDao().count())
    }

    @Test
    fun rescheduleAfterCompletion_isIdempotent() = runBlocking {
        val raw = LiveSmsProcessingHarness.liveSms()
        val intake = harness.intake(scheduler)
        intake.ingest(raw)
        assertEquals(WorkInfo.State.SUCCEEDED, finishedWork(raw.id).single().state)

        scheduler.schedule(raw.id)

        assertTrue(finishedWork(raw.id).all { it.state == WorkInfo.State.SUCCEEDED })
        assertEquals(1, harness.db.rawSmsDao().count())
        assertEquals(1, harness.db.parsedEventDao().count())
        assertEquals(1, harness.db.financialTransactionDao().count())
    }

    @Test
    fun startupSweep_schedulesOnlyEvidenceAwaitingProcessing() = runBlocking {
        val processed = LiveSmsProcessingHarness.liveSms()
        val orphan = LiveSmsProcessingHarness.liveSms(
            body = LiveSmsProcessingHarness.PURCHASE_BODY.replace("51.99", "12.00"),
            at = "2026-08-03T15:00:00Z",
        )
        harness.intake(scheduler).ingest(processed)
        assertEquals(WorkInfo.State.SUCCEEDED, finishedWork(processed.id).single().state)
        assertTrue(harness.capture.capture(orphan) is BankSmsCaptureResult.Captured)

        val scheduled = harness.intake(scheduler).schedulePendingProcessing()

        assertEquals(1, scheduled)
        assertEquals(WorkInfo.State.SUCCEEDED, finishedWork(orphan.id).single().state)
        assertEquals(1, finishedWork(processed.id).size)
        assertEquals(2, harness.db.financialTransactionDao().count())
    }

    private fun uniqueWork(rawSmsId: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkManagerLiveSmsWorkScheduler.uniqueWorkName(rawSmsId)).get()

    /** CoroutineWorker runs off the synchronous executor, so wait for the work to finish. */
    private fun finishedWork(rawSmsId: String): List<WorkInfo> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_TIMEOUT_SECONDS)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val infos = uniqueWork(rawSmsId)
            if (infos.all { it.state.isFinished } || System.nanoTime() > deadline) return infos
            Thread.sleep(SETTLE_POLL_MILLIS)
        }
    }

    private companion object {
        const val SETTLE_TIMEOUT_SECONDS = 5L
        const val SETTLE_POLL_MILLIS = 10L
    }
}
