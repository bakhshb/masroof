package com.baraa.masroof.application.maintenance

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.logging.AppLogService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun currentTransferIntegrityRepairCompletesBeforeStartup() = runBlocking<Unit> {
        var repairCount = 0
        val maintenance = startup(transferRepair { repairCount++ })

        assertEquals(StartupMaintenanceOutcome.READY, maintenance.runBlockingPhase())

        assertEquals(StartupMaintenanceOutcome.READY, maintenance.runBlockingPhase())
        assertEquals(1, repairCount)
        assertEquals(TransferIntegrityRepairCoordinator.CURRENT_VERSION, lastTransferRepairVersion())
    }

    @Test
    fun transferIntegrityRepair_failedRunBlocksStartupUntilRetry() = runBlocking<Unit> {
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

        fail = false
        assertEquals(StartupMaintenanceOutcome.READY, maintenance.runBlockingPhase())
        assertEquals(2, repairCount)
        assertEquals(TransferIntegrityRepairCoordinator.CURRENT_VERSION, lastTransferRepairVersion())
    }

    @Test fun startupWithoutPendingRepairIsReady() = runBlocking {
        assertEquals(StartupMaintenanceOutcome.READY, StartupMaintenance().runBlockingPhase())
    }

    private fun startup(repair: TransferIntegrityRepairCoordinator) = StartupMaintenance(repair)

    private fun transferRepair(
        block: () -> Unit,
    ) = TransferIntegrityRepairCoordinator(
        prefs = prefs,
        appLogService = AppLogService(context),
        repairStoredTransfers = {
            block()
            TransferIntegrityRepairResult()
        },
        completionSignal = MaintenanceCompletionSignal(),
    )

    private fun lastTransferRepairVersion() =
        prefs.getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0)

}
