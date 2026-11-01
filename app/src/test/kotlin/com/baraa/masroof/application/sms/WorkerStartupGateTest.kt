package com.baraa.masroof.application.sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.data.room.DatabaseRestartRequiredException
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Background financial work waits for integrity repair, survives a restore, and refreshes open screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkerStartupGateTest {
    private lateinit var context: Context
    private lateinit var harness: LiveSmsProcessingHarness
    private val signals = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        harness = LiveSmsProcessingHarness(context)
    }

    @After
    fun tearDown() = harness.close()

    @Test
    fun liveWorker_beforeIntegrityRepairSucceeds_retriesWithoutProcessing() = runBlocking {
        val raw = captured()

        val result = liveWorker(raw.id, ready = false).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(0, harness.parseCalls.get())
        assertNull(harness.parsedRepo.findByRawSmsId(raw.id))
        assertEquals(0, signals.get())
    }

    @Test
    fun liveWorker_whenARestoreRetiresTheDatabase_retries() = runBlocking {
        val raw = captured()
        val retired = object : RawSmsRepository by harness.rawRepo {
            override suspend fun getById(id: String): RawSms? = throw DatabaseRestartRequiredException()
        }

        val result = liveWorker(raw.id, rawRepository = retired).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun liveWorker_incompletePass_stillRefreshesOpenScreens() = runBlocking {
        val raw = captured()
        val processStored = harness.processStored(
            derivedFailures = DerivedFailureInjection(reconciliationFailuresRemaining = AtomicInteger(1)),
        )
        val worker = TestListenableWorkerBuilder<LiveSmsProcessingWorker>(context)
            .setInputData(LiveSmsProcessingWorker.inputFor(raw.id))
            .setWorkerFactory(LiveSmsProcessingWorker.Factory({ processStored }, harness.appLog) { signals.incrementAndGet() })
            .build()

        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(1, signals.get())
    }

    @Test
    fun historicalRecoveryWorker_signalsOnSuccessAndFailure_andWaitsForStartup() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), historicalWorker(harness.derivedRecovery()).doWork())
        assertEquals(1, signals.get())

        assertEquals(ListenableWorker.Result.retry(), historicalWorker(harness.derivedRecovery(), ready = false).doWork())
        assertEquals(1, signals.get())
    }

    private suspend fun captured(): RawSms {
        val raw = LiveSmsProcessingHarness.liveSms()
        assertEquals(true, harness.capture.capture(raw) is BankSmsCaptureResult.Captured)
        return raw
    }

    private fun liveWorker(rawSmsId: String, ready: Boolean = true, rawRepository: RawSmsRepository = harness.rawRepo) =
        TestListenableWorkerBuilder<LiveSmsProcessingWorker>(context)
            .setInputData(LiveSmsProcessingWorker.inputFor(rawSmsId))
            .setWorkerFactory(
                LiveSmsProcessingWorker.Factory({ harness.processStored(rawSmsRepository = rawRepository) }, harness.appLog, { ready }) {
                    signals.incrementAndGet()
                },
            )
            .build()

    private fun historicalWorker(recovery: HistoricalDerivedRecovery, ready: Boolean = true) =
        TestListenableWorkerBuilder<HistoricalDerivedRecoveryWorker>(context)
            .setWorkerFactory(
                HistoricalDerivedRecoveryWorker.Factory(
                    startupReady = { ready },
                    onFinished = { signals.incrementAndGet() },
                    recovery = { recovery },
                ),
            )
            .build()
}
