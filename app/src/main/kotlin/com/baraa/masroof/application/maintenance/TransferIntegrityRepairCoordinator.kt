package com.baraa.masroof.application.maintenance

import android.content.SharedPreferences
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-time blocking repair of transfer rows stored before M1 pairing rules.
 *
 * Reconcile itself is idempotent; this coordinator records a version so startup
 * does not sweep the ledger on every launch. Failed runs stay unrecorded so
 * the next attempt retries. Callers supply the reconcile-and-review lambda so
 * this type does not import transaction or review packages.
 */
class TransferIntegrityRepairCoordinator(
    private val prefs: SharedPreferences,
    private val appLogService: AppLogService,
    private val repairStoredTransfers: suspend () -> Unit,
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
        val succeeded = try {
            repairStoredTransfers()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogService.warn(
                AppLogCategories.TRANSACTION,
                "Transfer integrity repair failed: ${e.javaClass.simpleName}; will retry",
            )
            false
        } finally {
            completionSignal?.notifyCompleted()
        }
        if (!succeeded) return@withLock BackfillOutcome.INCOMPLETE

        recordRepairVersion(CURRENT_VERSION)
        appLogService.info(
            AppLogCategories.TRANSACTION,
            "Transfer integrity repair finished: v$CURRENT_VERSION",
        )
        BackfillOutcome.COMPLETED
    }

    private fun recordRepairVersion(version: Int) {
        prefs.edit()
            .putInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, version)
            .apply()
    }

    private fun lastRepairVersion(): Int =
        prefs.getInt(MaintenancePreferences.KEY_TRANSFER_INTEGRITY_REPAIR_VERSION, 0)

    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}
