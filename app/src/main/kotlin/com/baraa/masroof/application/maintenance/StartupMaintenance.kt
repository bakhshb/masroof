package com.baraa.masroof.application.maintenance

enum class StartupMaintenanceOutcome {
    READY,
    BLOCKED,
}

/** Financial screens stay blocked until the financial store opens; a store that cannot open throws (fails closed). */
class StartupMaintenance(private val openFinancialStore: suspend () -> Unit) {
    suspend fun runBlockingPhase(): StartupMaintenanceOutcome {
        openFinancialStore()
        return StartupMaintenanceOutcome.READY
    }
}
