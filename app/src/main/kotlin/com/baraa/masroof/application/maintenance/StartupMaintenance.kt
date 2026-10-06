package com.baraa.masroof.application.maintenance

import com.baraa.masroof.data.room.MasroofDatabase

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
    /** Returns once the UI may show financial data. */
    suspend fun runBlockingPhase() {
        when (factsBackfill.pendingRequirement(currentSchemaVersion)) {
            null -> Unit
            MaintenanceRequirement.BACKGROUND -> scheduleFactsBackfill()
            MaintenanceRequirement.BLOCKING ->
                if (factsBackfill.runIfNeeded(currentSchemaVersion) == BackfillOutcome.INCOMPLETE) {
                    // Failed rows cannot be fixed by waiting longer; retry them in the background.
                    scheduleFactsBackfill()
                }
        }
    }
}
