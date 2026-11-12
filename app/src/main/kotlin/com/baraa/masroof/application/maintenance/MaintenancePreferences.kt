package com.baraa.masroof.application.maintenance

object MaintenancePreferences {
    const val PREFS_NAME: String = "masroof_maintenance"

    /** Retired marker retained only for existing restore-journal compatibility. Never schedules work. */
    const val KEY_LAST_REPARSED_SCHEMA_VERSION: String = "last_reparsed_schema_version"

    /**
     * Retired marker of the one-time transfer-integrity repair (deleted in plan 3.3c; `LedgerProjector` now releases
     * double-posted single-leg self-transfers in every pass). Kept only for restore-journal compatibility; never
     * schedules work. The name stays reserved: never reuse it.
     */
    const val KEY_TRANSFER_INTEGRITY_REPAIR_VERSION: String = "transfer_integrity_repair_version"
}
