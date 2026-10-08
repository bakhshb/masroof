package com.baraa.masroof.application.maintenance

object MaintenancePreferences {
    const val PREFS_NAME: String = "masroof_maintenance"

    /** Last Room schema version for which stored RawSms evidence was re-parsed. */
    const val KEY_LAST_REPARSED_SCHEMA_VERSION: String = "last_reparsed_schema_version"

    /**
     * Last completed transfer-integrity repair pass.
     *
     * Pre-M1 ledgers may store one self-transfer per SMS leg. After upgrade this
     * pass unlinks irreducibly ambiguous legs into PENDING_MATCH review and
     * heals uniquely pairable legs, once, before financial screens are shown.
     */
    const val KEY_TRANSFER_INTEGRITY_REPAIR_VERSION: String = "transfer_integrity_repair_version"
}
