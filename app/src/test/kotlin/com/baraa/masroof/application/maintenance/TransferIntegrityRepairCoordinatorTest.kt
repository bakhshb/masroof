package com.baraa.masroof.application.maintenance

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.logging.AppLogService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransferIntegrityRepairCoordinatorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun pendingRequirement_isBlockingUntilThePassCompletes() = runBlocking<Unit> {
        var runs = 0
        val coordinator = coordinator {
            runs++
            TransferIntegrityRepairResult()
        }

        assertEquals(MaintenanceRequirement.BLOCKING, coordinator.pendingRequirement())
        assertEquals(BackfillOutcome.COMPLETED, coordinator.runIfNeeded())
        assertEquals(1, runs)
        assertNull(coordinator.pendingRequirement())
        assertEquals(BackfillOutcome.UP_TO_DATE, coordinator.runIfNeeded())
        assertEquals(1, runs)
        assertEquals(
            TransferIntegrityRepairCoordinator.CURRENT_VERSION,
            prefs().getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0),
        )
    }

    @Test
    fun runIfNeeded_reconcileFailuresStayPendingAndRetry() = runBlocking<Unit> {
        var attempts = 0
        val coordinator = coordinator {
            attempts++
            TransferIntegrityRepairResult(failedCount = if (attempts == 1) 2 else 0)
        }

        assertEquals(BackfillOutcome.INCOMPLETE, coordinator.runIfNeeded())
        assertEquals(MaintenanceRequirement.BLOCKING, coordinator.pendingRequirement())
        assertEquals(0, prefs().getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0))
        assertEquals(BackfillOutcome.COMPLETED, coordinator.runIfNeeded())
        assertEquals(2, attempts)
        assertNull(coordinator.pendingRequirement())
    }

    @Test
    fun runIfNeeded_failedRepairStaysPendingAndRetries() = runBlocking<Unit> {
        var attempts = 0
        val coordinator = coordinator {
            attempts++
            if (attempts == 1) error("database unavailable")
            TransferIntegrityRepairResult()
        }

        assertEquals(BackfillOutcome.INCOMPLETE, coordinator.runIfNeeded())
        assertEquals(MaintenanceRequirement.BLOCKING, coordinator.pendingRequirement())
        assertEquals(0, prefs().getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0))
        assertEquals(BackfillOutcome.COMPLETED, coordinator.runIfNeeded())
        assertEquals(2, attempts)
        assertNull(coordinator.pendingRequirement())
    }

    @Test
    fun runIfNeeded_commitFailureLeavesMarkerUnset() = runBlocking<Unit> {
        val coordinator = TransferIntegrityRepairCoordinator(
            prefs = CommitFailsPreferences(prefs()),
            appLogService = AppLogService(context),
            repairStoredTransfers = { TransferIntegrityRepairResult() },
        )

        assertEquals(BackfillOutcome.INCOMPLETE, coordinator.runIfNeeded())
        assertEquals(MaintenanceRequirement.BLOCKING, coordinator.pendingRequirement())
        assertEquals(0, prefs().getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0))
    }

    @Test
    fun runIfNeeded_signalsCompletionWhenItRepairs() = runBlocking<Unit> {
        val signal = MaintenanceCompletionSignal()
        val coordinator = TransferIntegrityRepairCoordinator(
            prefs = prefs(),
            appLogService = AppLogService(context),
            repairStoredTransfers = { TransferIntegrityRepairResult() },
            completionSignal = signal,
        )
        val completion = async(start = CoroutineStart.UNDISPATCHED) { signal.completions.first() }

        assertEquals(BackfillOutcome.COMPLETED, coordinator.runIfNeeded())
        withTimeout(1_000) { completion.await() }
    }

    private fun coordinator(
        block: () -> TransferIntegrityRepairResult = { TransferIntegrityRepairResult() },
    ) = TransferIntegrityRepairCoordinator(
        prefs = prefs(),
        appLogService = AppLogService(context),
        repairStoredTransfers = { block() },
    )

    private fun prefs() =
        context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private class CommitFailsPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = CommitFailsEditor(delegate.edit())
    }

    private class CommitFailsEditor(
        private val delegate: SharedPreferences.Editor,
    ) : SharedPreferences.Editor by delegate {
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            delegate.putInt(key, value)
            return this
        }

        override fun commit(): Boolean = false
    }
}
