package com.baraa.masroof.application.sms

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baraa.masroof.application.dashboard.ForeignSarMarketRateProvider
import com.baraa.masroof.application.dashboard.TransactionSarEquivalentResolver
import com.baraa.masroof.application.transaction.ExchangeRateEnrichmentWorkflow
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExchangeRateEnrichmentSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var harness: LiveSmsProcessingHarness
    private val enrichCalls = AtomicInteger(0)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        harness = LiveSmsProcessingHarness(context)
        val enricher = countingEnricher(harness, enrichCalls)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(ExchangeRateEnrichmentWorker.Factory { enricher })
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        harness.close()
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

    private fun finishedUniqueWork(): List<WorkInfo> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val infos = workManager
                .getWorkInfosForUniqueWork(WorkManagerExchangeRateEnrichmentScheduler.UNIQUE_WORK_NAME)
                .get()
            if (infos.all { it.state.isFinished } || System.nanoTime() > deadline) return infos
            Thread.sleep(10)
        }
    }

    @Test
    fun workRequest_isTaggedForDiscovery() {
        val request = WorkManagerExchangeRateEnrichmentScheduler.workRequest()
        assertTrue(WorkManagerExchangeRateEnrichmentScheduler.WORK_TAG in request.tags)
    }

    private fun countingEnricher(
        harness: LiveSmsProcessingHarness,
        counter: AtomicInteger,
    ): PendingExchangeRateEnricher {
        val ftRepo = object : FinancialTransactionRepository by harness.ftRepo {
            override suspend fun listAwaitingAppliedExchangeRate(
                primaryCurrency: Currency,
            ): List<FinancialTransaction> {
                counter.incrementAndGet()
                return harness.ftRepo.listAwaitingAppliedExchangeRate(primaryCurrency)
            }
        }
        val workflow = ExchangeRateEnrichmentWorkflow(
            financialTransactionRepository = ftRepo,
            parsedEventRepository = harness.parsedRepo,
            rawSmsRepository = harness.rawRepo,
            sarEquivalentResolver = TransactionSarEquivalentResolver(
                marketRateProvider = ForeignSarMarketRateProvider { _, _ -> null },
            ),
        )
        return PendingExchangeRateEnricher { workflow.enrichPending() }
    }
}
