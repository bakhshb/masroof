package com.baraa.masroof.application

import com.baraa.masroof.application.container.MaintenanceContainer
import com.baraa.masroof.application.container.appWorkerFactory
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome
import com.baraa.masroof.data.room.MasroofDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
                .setWorkerFactory(appWorkerFactory(container))
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        container.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test fun retiredSchemaMarkersCannotTriggerBackfill() = runBlocking {
        recordLastReparsedVersion(9)
        container.maintenance.runStartupMaintenance()
        assertEquals(StartupMaintenanceOutcome.READY, withTimeout(STARTUP_TIMEOUT_MILLIS) { container.maintenance.awaitStartupMaintenance() })
        assertTrue(backfillWork().isEmpty())
        assertEquals(9, lastReparsedVersion())
    }

    @Test
    fun upToDate_schedulesNoBackfill() = runBlocking<Unit> {
        recordLastReparsedVersion(MasroofDatabase.VERSION)

        container.maintenance.runStartupMaintenance()
        assertEquals(
            StartupMaintenanceOutcome.READY,
            withTimeout(STARTUP_TIMEOUT_MILLIS) { container.maintenance.awaitStartupMaintenance() },
        )

        assertTrue(backfillWork().isEmpty())
    }

    @Test
    fun postStartupBarrier_finishesBeforeNewCaptureCanBeScheduledByStartup() = runBlocking<Unit> {
        recordLastReparsedVersion(MasroofDatabase.VERSION)
        container.maintenance.runStartupMaintenance()
        assertEquals(
            StartupMaintenanceOutcome.READY,
            withTimeout(STARTUP_TIMEOUT_MILLIS) { container.maintenance.awaitStartupMaintenance() },
        )
        withTimeout(STARTUP_TIMEOUT_MILLIS) { container.maintenance.awaitPostStartupBackgroundWorkForTesting() }
        // Joining again is immediate; there is no delayed startup scheduling left.
        withTimeout(STARTUP_TIMEOUT_MILLIS) { container.maintenance.awaitPostStartupBackgroundWorkForTesting() }
    }

    @Test
    fun aFinancialStoreThatCannotOpen_blocksStartup_andIsPreserved() = runBlocking {
        val file = context.getDatabasePath(MasroofDatabase.NAME).apply { parentFile!!.mkdirs() }
        val bytes = ("SQLite format 3\u0000" + "damaged-sqlite-test-file".repeat(100)).toByteArray()
        file.writeBytes(bytes)
        container.maintenance.runStartupMaintenance()
        assertEquals(
            StartupMaintenanceOutcome.BLOCKED,
            withTimeout(STARTUP_TIMEOUT_MILLIS) { container.maintenance.awaitStartupMaintenance() },
        )
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun successfulRetry_updatesTheAuthorityReadByRecreatedActivities() = runBlocking {
        var storeOpens = false
        val maintenance = MaintenanceContainer(
            container.storage,
            openFinancialStore = { check(storeOpens) { "file is not a database" } },
            runPostStartupBackgroundWork = {},
            isOpen = { true },
        )
        maintenance.runStartupMaintenance()
        assertEquals(StartupMaintenanceOutcome.BLOCKED, maintenance.awaitStartupMaintenance())
        storeOpens = true
        assertEquals(StartupMaintenanceOutcome.READY, maintenance.retryStartupMaintenance())
        assertEquals(StartupMaintenanceOutcome.READY, maintenance.awaitStartupMaintenance())
        assertEquals(StartupMaintenanceOutcome.READY, maintenance.awaitStartupMaintenance())
    }

    private fun backfillWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork("parsed-event-facts-backfill").get()

    private fun prefs() = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private fun recordLastReparsedVersion(version: Int) {
        prefs().edit().putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, version).commit()
    }

    private fun lastReparsedVersion() = prefs().getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0)

    private companion object {
        const val STARTUP_TIMEOUT_MILLIS = 10_000L
    }
}
