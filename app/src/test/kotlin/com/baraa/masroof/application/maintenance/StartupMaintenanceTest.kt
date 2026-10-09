package com.baraa.masroof.application.maintenance

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.logging.AppLogService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StartupMaintenanceTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private var reparseCount = 0
    private var scheduleCount = 0
    private var failedRows = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun upToDate_neitherReparsesNorSchedules() = runBlocking<Unit> {
        recordLastReparsedVersion(CURRENT_VERSION)

        assertEquals(StartupMaintenanceOutcome.READY, startup().runBlockingPhase())

        assertEquals(0, reparseCount)
        assertEquals(0, scheduleCount)
    }

    @Test
    fun backgroundSafeBacklog_doesNotHoldStartup() = runBlocking<Unit> {
        recordLastReparsedVersion(11)

        assertEquals(StartupMaintenanceOutcome.READY, startup().runBlockingPhase())

        assertEquals(0, reparseCount)
        assertEquals(1, scheduleCount)
        assertEquals(11, lastReparsedVersion())
    }

    @Test
    fun correctnessBlockingBacklog_finishesBeforeStartupReturns() = runBlocking<Unit> {
        recordLastReparsedVersion(9)

        assertEquals(StartupMaintenanceOutcome.READY, startup().runBlockingPhase())

        assertEquals(1, reparseCount)
        assertEquals(0, scheduleCount)
        assertEquals(CURRENT_VERSION, lastReparsedVersion())
    }

    @Test
    fun freshInstall_isBlockingAndCompletesInline() = runBlocking<Unit> {
        assertEquals(StartupMaintenanceOutcome.READY, startup().runBlockingPhase())

        assertEquals(1, reparseCount)
        assertEquals(0, scheduleCount)
    }

    @Test
    fun indexOnlyUpgrade_recordsSchemaWithoutSchedulingOrRunningReparse() = runBlocking<Unit> {
        recordLastReparsedVersion(16)
        val coordinator = coordinator()
        val maintenance = StartupMaintenance(
            factsBackfill = coordinator,
            currentSchemaVersion = 17,
        ) { scheduleCount++ }

        assertEquals(MaintenanceRequirement.NOT_REQUIRED, coordinator.pendingRequirement(17))
        assertEquals(StartupMaintenanceOutcome.READY, maintenance.runBlockingPhase())

        assertEquals(0, reparseCount)
        assertEquals(0, scheduleCount)
        assertEquals(17, lastReparsedVersion())
        assertNull(coordinator.pendingRequirement(17))
    }

    @Test
    fun blockingBacklogWithFailedRows_staysBlockedUntilRetrySucceeds() = runBlocking<Unit> {
        recordLastReparsedVersion(9)
        failedRows = 2
        val coordinator = coordinator()

        val maintenance = StartupMaintenance(
            factsBackfill = coordinator,
            currentSchemaVersion = CURRENT_VERSION,
        ) { scheduleCount++ }
        assertEquals(StartupMaintenanceOutcome.BLOCKED, maintenance.runBlockingPhase())

        assertEquals(1, reparseCount)
        assertEquals(0, scheduleCount)
        assertEquals(9, lastReparsedVersion())
        assertEquals(MaintenanceRequirement.BLOCKING, coordinator.pendingRequirement(CURRENT_VERSION))

        failedRows = 0
        assertEquals(StartupMaintenanceOutcome.READY, maintenance.runBlockingPhase())
        assertEquals(2, reparseCount)
        assertEquals(0, scheduleCount)
        assertNull(coordinator.pendingRequirement(CURRENT_VERSION))
    }

    @Test
    fun transferIntegrityRepair_runsAfterFactsAreReady() = runBlocking<Unit> {
        recordLastReparsedVersion(CURRENT_VERSION)
        var repairCount = 0
        val maintenance = startup(transferRepair { repairCount++ })

        assertEquals(StartupMaintenanceOutcome.READY, maintenance.runBlockingPhase())

        assertEquals(0, reparseCount)
        assertEquals(1, repairCount)
        assertEquals(TransferIntegrityRepairCoordinator.CURRENT_VERSION, lastTransferRepairVersion())
    }

    @Test
    fun transferIntegrityRepair_failedRunBlocksStartupUntilRetry() = runBlocking<Unit> {
        recordLastReparsedVersion(CURRENT_VERSION)
        var fail = true
        var repairCount = 0
        val repair = transferRepair {
            repairCount++
            if (fail) error("ledger locked")
        }
        val maintenance = startup(repair)

        assertEquals(StartupMaintenanceOutcome.BLOCKED, maintenance.runBlockingPhase())
        assertEquals(1, repairCount)
        assertEquals(0, lastTransferRepairVersion())
        assertEquals(MaintenanceRequirement.BLOCKING, repair.pendingRequirement())

        fail = false
        assertEquals(StartupMaintenanceOutcome.READY, maintenance.runBlockingPhase())
        assertEquals(2, repairCount)
        assertNull(repair.pendingRequirement())
    }

    @Test
    fun transferIntegrityRepair_doesNotRunWhenFactsStayBlocked() = runBlocking<Unit> {
        recordLastReparsedVersion(9)
        failedRows = 2
        var repairCount = 0
        val maintenance = StartupMaintenance(
            factsBackfill = coordinator(),
            transferIntegrityRepair = transferRepair { repairCount++ },
            currentSchemaVersion = CURRENT_VERSION,
        ) { scheduleCount++ }

        assertEquals(StartupMaintenanceOutcome.BLOCKED, maintenance.runBlockingPhase())
        assertEquals(0, repairCount)
        assertEquals(0, lastTransferRepairVersion())
    }

    private fun startup(
        transferIntegrityRepair: TransferIntegrityRepairCoordinator? = null,
    ) = StartupMaintenance(
        factsBackfill = coordinator(),
        transferIntegrityRepair = transferIntegrityRepair,
        currentSchemaVersion = CURRENT_VERSION,
    ) { scheduleCount++ }

    private fun transferRepair(
        block: () -> Unit,
    ) = TransferIntegrityRepairCoordinator(
        prefs = prefs,
        appLogService = AppLogService(context),
        repairStoredTransfers = {
            block()
            TransferIntegrityRepairResult()
        },
    )

    private fun coordinator() = ParsedEventFactsBackfillCoordinator(
        prefs = prefs,
        appLogService = AppLogService(context),
        reparseAllStoredEvents = {
            reparseCount++
            ReparseAllStoredEventsResult(refreshedCount = 1, failedCount = failedRows)
        },
    )

    private fun recordLastReparsedVersion(version: Int) {
        prefs.edit().putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, version).commit()
    }

    private fun lastReparsedVersion() = prefs.getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0)

    private fun lastTransferRepairVersion() =
        prefs.getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0)

    private companion object {
        const val CURRENT_VERSION = 14
    }
}
