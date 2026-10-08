package com.baraa.masroof.application.maintenance

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.sms.LiveSmsProcessingWorker
import com.baraa.masroof.data.room.MasroofDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class ParsedEventFactsBackfillWorkerTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var coordinator: ParsedEventFactsBackfillCoordinator
    private var reparseCount = 0
    private var failedRows = 0
    private var workManagerInitialized = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        coordinator = ParsedEventFactsBackfillCoordinator(
            prefs = prefs,
            appLogService = AppLogService(context),
            reparseAllStoredEvents = {
                reparseCount++
                ReparseAllStoredEventsResult(refreshedCount = 1, failedCount = failedRows)
            },
        )
    }

    @After
    fun tearDown() {
        if (workManagerInitialized) WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun pendingBackfill_succeedsAndRecordsSchemaVersion() = runBlocking<Unit> {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(1, reparseCount)
        assertNull(coordinator.pendingRequirement())
        assertEquals(MasroofDatabase.VERSION, lastReparsedVersion())
    }

    @Test
    fun upToDate_succeedsWithoutReparse() = runBlocking<Unit> {
        prefs.edit().putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, MasroofDatabase.VERSION).commit()

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, reparseCount)
    }

    @Test
    fun failedRows_retryThenGiveUpWithoutRecordingVersion() = runBlocking<Unit> {
        failedRows = 1

        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 0).doWork())
        assertEquals(
            ListenableWorker.Result.failure(),
            worker(attempt = ParsedEventFactsBackfillWorker.MAX_ATTEMPTS - 1).doWork(),
        )
        assertEquals(0, lastReparsedVersion())
        assertEquals(MaintenanceRequirement.BLOCKING, coordinator.pendingRequirement())
    }

    @Test
    fun retryAfterFailedRows_completesIdempotently() = runBlocking<Unit> {
        failedRows = 1
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 0).doWork())

        failedRows = 0
        assertEquals(ListenableWorker.Result.success(), worker(attempt = 1).doWork())
        assertEquals(ListenableWorker.Result.success(), worker(attempt = 0).doWork())

        assertEquals(2, reparseCount)
        assertEquals(MasroofDatabase.VERSION, lastReparsedVersion())
    }

    @Test
    fun factory_leavesOtherWorkersToLaterFactories() {
        var liveFactoryUsed = false
        val factory = DelegatingWorkerFactory().apply {
            addFactory(ParsedEventFactsBackfillWorker.Factory { error("must not build other workers") })
            addFactory(
                LiveSmsProcessingWorker.Factory({
                    liveFactoryUsed = true
                    error("live processing not used")
                }),
            )
        }

        runCatching {
            TestListenableWorkerBuilder<LiveSmsProcessingWorker>(context).setWorkerFactory(factory).build()
        }

        assertTrue(liveFactoryUsed)
    }

    @Test
    fun workRequest_usesExponentialBackoff() {
        val spec = ParsedEventFactsBackfillWorker.workRequest().workSpec

        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(ParsedEventFactsBackfillWorker.BACKOFF_DELAY_SECONDS * 1_000, spec.backoffDelayDuration)
    }

    @Test
    fun enqueuedBackfill_runsThroughDelegatingFactory_andStaysUnique() {
        val workManager = initializeWorkManager()

        ParsedEventFactsBackfillWorker.enqueue(workManager)
        assertEquals(WorkInfo.State.SUCCEEDED, finishedWork(workManager).single().state)

        ParsedEventFactsBackfillWorker.enqueue(workManager)
        assertEquals(WorkInfo.State.SUCCEEDED, finishedWork(workManager).single().state)

        assertEquals(1, reparseCount)
        assertEquals(MasroofDatabase.VERSION, lastReparsedVersion())
    }

    private fun worker(attempt: Int = 0): ParsedEventFactsBackfillWorker =
        TestListenableWorkerBuilder<ParsedEventFactsBackfillWorker>(context)
            .setWorkerFactory(ParsedEventFactsBackfillWorker.Factory { coordinator })
            .setRunAttemptCount(attempt)
            .build()

    private fun initializeWorkManager(): WorkManager {
        val factory = DelegatingWorkerFactory().apply {
            addFactory(LiveSmsProcessingWorker.Factory({ error("not used") }))
            addFactory(ParsedEventFactsBackfillWorker.Factory { coordinator })
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
        workManagerInitialized = true
        return WorkManager.getInstance(context)
    }

    /** CoroutineWorker runs off the synchronous executor, so wait for the work to finish. */
    private fun finishedWork(workManager: WorkManager): List<WorkInfo> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_TIMEOUT_SECONDS)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val infos = workManager
                .getWorkInfosForUniqueWork(ParsedEventFactsBackfillWorker.UNIQUE_WORK_NAME)
                .get()
            if (infos.all { it.state.isFinished } || System.nanoTime() > deadline) return infos
            Thread.sleep(SETTLE_POLL_MILLIS)
        }
    }

    private fun lastReparsedVersion() = prefs.getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0)

    private companion object {
        const val SETTLE_TIMEOUT_SECONDS = 5L
        const val SETTLE_POLL_MILLIS = 10L
    }
}
