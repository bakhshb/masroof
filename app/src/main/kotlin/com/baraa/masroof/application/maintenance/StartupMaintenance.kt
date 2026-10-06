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
    private val currentSchemaVersion: Int = MasroofDatabase.VERSION,
    private val scheduleFactsBackfill: () -> Unit,
) {
    /**
     * Returns READY only when financial data is safe to display.
     * A failed BLOCKING backfill fails closed; callers show an explicit retry state.
     */
    suspend fun runBlockingPhase(): StartupMaintenanceOutcome =
        when (factsBackfill.pendingRequirement(currentSchemaVersion)) {
            null -> StartupMaintenanceOutcome.READY
            MaintenanceRequirement.BACKGROUND -> {
                scheduleFactsBackfill()
                StartupMaintenanceOutcome.READY
            }
            MaintenanceRequirement.BLOCKING ->
                when (factsBackfill.runIfNeeded(currentSchemaVersion)) {
                    BackfillOutcome.UP_TO_DATE,
                    BackfillOutcome.COMPLETED,
                    -> StartupMaintenanceOutcome.READY

                    BackfillOutcome.INCOMPLETE -> StartupMaintenanceOutcome.BLOCKED
                }
        }
}
