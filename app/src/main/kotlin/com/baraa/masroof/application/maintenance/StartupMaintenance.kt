package com.baraa.masroof.application.maintenance

import com.baraa.masroof.data.room.MasroofDatabase

enum class StartupMaintenanceOutcome {
    READY,
    BLOCKED,
}

/**
 * Startup maintenance policy: runs [MaintenanceRequirement.BLOCKING] work before the launch UI
 * shows financial data and hands [MaintenanceRequirement.BACKGROUND] work to retryable
 * background execution.
 */
class StartupMaintenance(
    private val factsBackfill: ParsedEventFactsBackfillCoordinator,
    private val transferIntegrityRepair: TransferIntegrityRepairCoordinator? = null,
    private val currentSchemaVersion: Int = MasroofDatabase.VERSION,
    private val scheduleFactsBackfill: () -> Unit,
) {
    /**
     * Returns READY only when financial data is safe to display.
     * A failed BLOCKING backfill or transfer-integrity repair fails closed;
     * callers show an explicit retry state.
     */
    suspend fun runBlockingPhase(): StartupMaintenanceOutcome {
        if (!runFactsBlockingPhase()) return StartupMaintenanceOutcome.BLOCKED
        return runTransferIntegrityBlockingPhase()
    }

    private suspend fun runFactsBlockingPhase(): Boolean =
        when (factsBackfill.pendingRequirement(currentSchemaVersion)) {
            null -> true
            MaintenanceRequirement.NOT_REQUIRED,
            MaintenanceRequirement.BLOCKING,
            -> when (factsBackfill.runIfNeeded(currentSchemaVersion)) {
                BackfillOutcome.UP_TO_DATE,
                BackfillOutcome.COMPLETED,
                -> true

                BackfillOutcome.INCOMPLETE -> false
            }
            MaintenanceRequirement.BACKGROUND -> {
                scheduleFactsBackfill()
                true
            }
        }

    private suspend fun runTransferIntegrityBlockingPhase(): StartupMaintenanceOutcome {
        val repair = transferIntegrityRepair ?: return StartupMaintenanceOutcome.READY
        return when (repair.pendingRequirement()) {
            null -> StartupMaintenanceOutcome.READY
            MaintenanceRequirement.NOT_REQUIRED,
            MaintenanceRequirement.BACKGROUND,
            -> StartupMaintenanceOutcome.READY
            MaintenanceRequirement.BLOCKING ->
                when (repair.runIfNeeded()) {
                    BackfillOutcome.UP_TO_DATE,
                    BackfillOutcome.COMPLETED,
                    -> StartupMaintenanceOutcome.READY

                    BackfillOutcome.INCOMPLETE -> StartupMaintenanceOutcome.BLOCKED
                }
        }
    }
}
