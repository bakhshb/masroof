package com.baraa.masroof.application

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.maintenance.ParsedEventFactsBackfillWorker
import com.baraa.masroof.data.room.MasroofDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** Startup maintenance through the real [AppContainer] wiring and WorkManager worker factory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppContainerStartupMaintenanceTest {
    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = (context as MasroofApplication).container
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(container.workerFactory)
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    /**
     * Cancels instead of [AppContainer.close]: closing Room while a cancelled startup query
     * still holds its connection lock deadlocks under Robolectric.
     */
    @After
    fun tearDown() {
        container.applicationScope.cancel()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun backgroundSafeBacklog_releasesStartup_andBackfillsThroughWorker() = runBlocking<Unit> {
        recordLastReparsedVersion(11)
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            container.maintenanceCompletionSignal.completions.first()
        }

        container.runStartupMaintenance()
        withTimeout(STARTUP_TIMEOUT_MILLIS) { container.awaitStartupMaintenance() }

        assertEquals(WorkInfo.State.SUCCEEDED, finishedBackfillWork().single().state)
        assertEquals(MasroofDatabase.VERSION, lastReparsedVersion())
        withTimeout(STARTUP_TIMEOUT_MILLIS) { completion.await() }
    }

    @Test
    fun correctnessBlockingBacklog_finishesBeforeStartupIsReleased() = runBlocking<Unit> {
        recordLastReparsedVersion(9)

        container.runStartupMaintenance()
        withTimeout(STARTUP_TIMEOUT_MILLIS) { container.awaitStartupMaintenance() }

        assertEquals(MasroofDatabase.VERSION, lastReparsedVersion())
        assertTrue(backfillWork().isEmpty())
    }

    @Test
    fun upToDate_schedulesNoBackfill() = runBlocking<Unit> {
        recordLastReparsedVersion(MasroofDatabase.VERSION)

        container.runStartupMaintenance()
        withTimeout(STARTUP_TIMEOUT_MILLIS) { container.awaitStartupMaintenance() }

        assertTrue(backfillWork().isEmpty())
    }

    private fun backfillWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(ParsedEventFactsBackfillWorker.UNIQUE_WORK_NAME).get()

    /** CoroutineWorker runs off the synchronous executor, so wait for the work to finish. */
    private fun finishedBackfillWork(): List<WorkInfo> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_TIMEOUT_MILLIS)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val infos = backfillWork()
            if ((infos.isNotEmpty() && infos.all { it.state.isFinished }) || System.nanoTime() > deadline) {
                return infos
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun prefs() = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private fun recordLastReparsedVersion(version: Int) {
        prefs().edit().putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, version).commit()
    }

    private fun lastReparsedVersion() = prefs().getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0)

    private companion object {
        const val STARTUP_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 10L
    }
}
