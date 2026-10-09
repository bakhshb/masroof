package com.baraa.masroof.application.maintenance

import android.content.SharedPreferences
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Counts operations the repair pass could not finish.
 * A positive [failedCount] keeps the repair unmarked so startup retries it.
 */
data class TransferIntegrityRepairResult(
    val failedCount: Int = 0,
) {
    val succeeded: Boolean get() = failedCount == 0
}

/**
 * One-time blocking repair of transfer rows stored before M1 pairing rules.
 *
 * Reconcile itself is idempotent; this coordinator records a version so startup
 * does not sweep the ledger on every launch. A thrown run or a reconcile report
 * with failed operations stays unrecorded so the next attempt retries. Callers
 * supply the reconcile-and-review lambda so this type does not import
 * transaction or review packages.
 */
class TransferIntegrityRepairCoordinator(
    private val prefs: SharedPreferences,
    private val appLogService: AppLogService,
    private val repairStoredTransfers: suspend () -> TransferIntegrityRepairResult,
    private val completionSignal: MaintenanceCompletionSignal? = null,
) {
    private val mutex = Mutex()

    fun pendingRequirement(): MaintenanceRequirement? =
        if (lastRepairVersion() >= CURRENT_VERSION) {
            null
        } else {
            MaintenanceRequirement.BLOCKING
        }

    suspend fun runIfNeeded(): BackfillOutcome = mutex.withLock {
        if (lastRepairVersion() >= CURRENT_VERSION) return@withLock BackfillOutcome.UP_TO_DATE

        appLogService.info(
            AppLogCategories.TRANSACTION,
            "Transfer integrity repair started: v${lastRepairVersion()} -> v$CURRENT_VERSION",
        )
        val result = try {
            repairStoredTransfers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogService.warn(
                AppLogCategories.TRANSACTION,
                "Transfer integrity repair failed: ${e.javaClass.simpleName}; will retry",
            )
            null
        } finally {
            completionSignal?.notifyCompleted()
        }
        if (result == null) return@withLock BackfillOutcome.INCOMPLETE
        if (!result.succeeded) {
            appLogService.warn(
                AppLogCategories.TRANSACTION,
                "Transfer integrity repair incomplete: ${result.failedCount} operations failed; will retry",
            )
            return@withLock BackfillOutcome.INCOMPLETE
        }

        if (!recordRepairVersion(CURRENT_VERSION)) {
            appLogService.warn(
                AppLogCategories.TRANSACTION,
                "Transfer integrity repair could not record completion; will retry",
            )
            return@withLock BackfillOutcome.INCOMPLETE
        }
        appLogService.info(
            AppLogCategories.TRANSACTION,
            "Transfer integrity repair finished: v$CURRENT_VERSION",
        )
        BackfillOutcome.COMPLETED
    }

    private fun recordRepairVersion(version: Int): Boolean =
        prefs.edit()
            .putInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, version)
            .commit()

    private fun lastRepairVersion(): Int =
        prefs.getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0)

    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}
