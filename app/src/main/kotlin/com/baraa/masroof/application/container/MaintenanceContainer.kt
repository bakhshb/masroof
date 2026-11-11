package com.baraa.masroof.application.container

import android.content.Context
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.maintenance.MaintenanceCompletionSignal
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.maintenance.StartupMaintenance
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome
import com.baraa.masroof.application.maintenance.TransferIntegrityRepairCoordinator
import com.baraa.masroof.application.maintenance.TransferIntegrityRepairResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Startup integrity repair and its outcome, plus the signal open screens reload on. Background financial
 * writes wait for, and require, a successful repair.
 */
class MaintenanceContainer(
    private val storage: StorageContainer,
    private val repairStoredTransfers: suspend () -> TransferIntegrityRepairResult,
    private val runPostStartupBackgroundWork: suspend () -> Unit,
    private val isOpen: () -> Boolean,
) {
    /** Emits when background maintenance changed stored data; open screens reload on it. */
    val completionSignal: MaintenanceCompletionSignal = MaintenanceCompletionSignal()

    private val transferIntegrityRepairCoordinator: TransferIntegrityRepairCoordinator by lazy {
        TransferIntegrityRepairCoordinator(
            prefs = storage.appContext.getSharedPreferences(
                MaintenancePreferences.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
            appLogService = storage.appLogService,
            repairStoredTransfers = repairStoredTransfers,
            completionSignal = completionSignal,
        )
    }

    private val startupMaintenance: StartupMaintenance by lazy {
        StartupMaintenance(transferIntegrityRepair = transferIntegrityRepairCoordinator)
    }

    private val startupMaintenanceOutcome = MutableStateFlow<StartupMaintenanceOutcome?>(null)
    private var startupMaintenanceJob: Job? = null

    internal val startupCancelled: Boolean get() = startupMaintenanceJob?.isCancelled == true

    fun runStartupMaintenance() {
        startupMaintenanceJob = storage.applicationScope.launch {
            val outcome = runStartupMaintenanceAttempt()
            startupMaintenanceOutcome.value = outcome
            if (outcome == StartupMaintenanceOutcome.READY) {
                runPostStartupBackgroundWork()
            }
        }
    }

    /**
     * Retry a previously blocked startup maintenance attempt from the gated UI. The work runs in the
     * application scope, so a rotation that cancels the caller does not stop it between repair and rescheduling.
     */
    suspend fun retryStartupMaintenance(): StartupMaintenanceOutcome = storage.applicationScope.async {
        val outcome = runStartupMaintenanceAttempt()
        startupMaintenanceOutcome.value = outcome
        if (outcome == StartupMaintenanceOutcome.READY) {
            runPostStartupBackgroundWork()
        }
        outcome
    }.await()

    private suspend fun runStartupMaintenanceAttempt(): StartupMaintenanceOutcome =
        try {
            startupMaintenance.runBlockingPhase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            storage.appLogService.warn(
                AppLogCategories.PARSE,
                "Blocking startup maintenance failed: ${e.javaClass.simpleName}",
            )
            StartupMaintenanceOutcome.BLOCKED
        }

    /** Test barrier: initial pending scheduling must finish before a capture halt is armed. */
    suspend fun awaitPostStartupBackgroundWorkForTesting() {
        checkNotNull(startupMaintenanceJob) { "Startup maintenance has not been launched" }.join()
    }

    /** Background financial writes wait for, and require, a successful integrity repair. */
    suspend fun startupFinancialReady(): Boolean = awaitStartupMaintenance() == StartupMaintenanceOutcome.READY

    /** Returns the safety outcome of the initial startup maintenance attempt. */
    suspend fun awaitStartupMaintenance(): StartupMaintenanceOutcome =
        startupMaintenanceOutcome.filterNotNull().first().let {
            if (isOpen()) it else StartupMaintenanceOutcome.BLOCKED
        }

    /** Shutdown: let a running startup finish, then release readers that were waiting for it. */
    fun drainStartup() {
        // Room cancellation can finish the coroutine before its first SQLite connection opens.
        // Drain startup normally so close cannot take the write lock during that callback.
        runBlocking(Dispatchers.IO) {
            startupMaintenanceJob?.join()
        }
        block()
    }

    /** Releases every reader that waits for startup; used on shutdown. */
    fun block() {
        startupMaintenanceOutcome.value = StartupMaintenanceOutcome.BLOCKED
    }
}
