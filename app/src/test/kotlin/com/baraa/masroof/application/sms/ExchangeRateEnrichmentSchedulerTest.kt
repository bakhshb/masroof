package com.baraa.masroof.application.sms

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExchangeRateEnrichmentSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var harness: LiveSmsProcessingHarness
    private val enrichCalls = AtomicInteger(0)
    private val executor = Executors.newSingleThreadExecutor()
    private var blockFirstPass: CountDownLatch? = null
    private var releaseFirstPass: CountDownLatch? = null
    private var releaseCompletion: CountDownLatch? = null

    @Before
    fun setUp() {
        ExchangeRateEnrichmentGate.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        harness = LiveSmsProcessingHarness(context)
        val enricher = blockingEnricher()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(executor)
                .setWorkerFactory(ExchangeRateEnrichmentWorker.Factory { enricher })
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        releaseFirstPass?.countDown()
        releaseCompletion?.countDown()
        ExchangeRateEnrichmentGate.resetForTests()
        WorkManagerTestInitHelper.closeWorkDatabase()
        harness.close()
        executor.shutdownNow()
    }

    @Test
    fun burstSchedules_coalesceToSingleUniqueWork() {
        val scheduler = WorkManagerExchangeRateEnrichmentScheduler { workManager }
        val pending = OneTimeWorkRequestBuilder<ExchangeRateEnrichmentWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        workManager.enqueueUniqueWork(
            WorkManagerExchangeRateEnrichmentScheduler.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            pending,
        ).result.get()

        scheduler.schedule()
        scheduler.schedule()
        scheduler.schedule()

        val infos = workManager
            .getWorkInfosForUniqueWork(WorkManagerExchangeRateEnrichmentScheduler.UNIQUE_WORK_NAME)
            .get()
        assertEquals(listOf(pending.id), infos.map { it.id })
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(0, enrichCalls.get())

        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(pending.id)

        val finished = finishedUniqueWork().single()
        assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
        assertEquals(1, enrichCalls.get())
    }

    private fun assertSucceeded(infos: List<WorkInfo>) {
        assertTrue(infos.isNotEmpty())
        assertTrue(infos.all { it.state == WorkInfo.State.SUCCEEDED })
    }

    private fun uniqueWork(): List<WorkInfo> =
        workManager
            .getWorkInfosForUniqueWork(WorkManagerExchangeRateEnrichmentScheduler.UNIQUE_WORK_NAME)
            .get()

    private fun finishedUniqueWork(): List<WorkInfo> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val infos = uniqueWork()
            val finished = infos.isNotEmpty() && infos.all { it.state.isFinished }
            if (finished || System.nanoTime() > deadline) return infos
            Thread.sleep(10)
        }
    }

    @Test
    fun scheduleWhileRunning_readsPendingRowsAgain() {
        blockFirstPass = CountDownLatch(1)
        releaseFirstPass = CountDownLatch(1)
        val scheduler = WorkManagerExchangeRateEnrichmentScheduler { workManager }

        scheduler.schedule()
        assertTrue(blockFirstPass!!.await(5, TimeUnit.SECONDS))
        scheduler.schedule()
        releaseFirstPass!!.countDown()

        val finished = finishedUniqueWork().single()
        assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
        assertEquals(2, enrichCalls.get())
    }

    @Test
    fun scheduleDuringCompletion_queuesFollowUpBeforeWorkManagerFinishes() {
        val enteredCompletion = CountDownLatch(1)
        releaseCompletion = CountDownLatch(1)
        ExchangeRateEnrichmentGate.setSuccessPauseForTests {
            ExchangeRateEnrichmentGate.setSuccessPauseForTests(null)
            enteredCompletion.countDown()
            releaseCompletion?.await(5, TimeUnit.SECONDS)
        }
        val scheduler = WorkManagerExchangeRateEnrichmentScheduler { workManager }

        scheduler.schedule()
        assertTrue(enteredCompletion.await(5, TimeUnit.SECONDS))
        val finishing = uniqueWork()
        assertEquals(1, finishing.size)
        assertFalse(finishing.single().state.isFinished)

        scheduler.schedule()

        val duringCompletion = uniqueWork()
        val finishingId = finishing.single().id
        assertTrue(duringCompletion.any { it.id == finishingId && !it.state.isFinished })
        assertTrue(duringCompletion.any { it.id != finishingId && !it.state.isFinished })
        releaseCompletion!!.countDown()

        assertSucceeded(finishedUniqueWork())
        assertEquals(2, enrichCalls.get())

        scheduler.schedule()
        assertSucceeded(finishedUniqueWork())
        assertEquals(3, enrichCalls.get())
    }

    @Test
    fun initialEnqueueFailure_allowsALaterSchedule() {
        var fail = true
        val scheduler = WorkManagerExchangeRateEnrichmentScheduler {
            if (fail) throw IllegalStateException("workmanager unavailable")
            workManager
        }

        val failure = assertThrows(IllegalStateException::class.java) { scheduler.schedule() }
        assertEquals("workmanager unavailable", failure.message)
        assertEquals(0, enrichCalls.get())

        fail = false
        scheduler.schedule()

        assertSucceeded(finishedUniqueWork())
        assertEquals(1, enrichCalls.get())
    }

    @Test
    fun workRequest_isTaggedForDiscovery() {
        val request = WorkManagerExchangeRateEnrichmentScheduler.workRequest()
        assertTrue(WorkManagerExchangeRateEnrichmentScheduler.WORK_TAG in request.tags)
    }

    private fun blockingEnricher(): PendingExchangeRateEnricher =
        PendingExchangeRateEnricher {
            val call = enrichCalls.incrementAndGet()
            if (call == 1) {
                blockFirstPass?.countDown()
                releaseFirstPass?.await()
            }
        }
}
