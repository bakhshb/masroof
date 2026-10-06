package com.baraa.masroof.application.maintenance

/** Whether the launch UI must wait for a maintenance task before showing financial data. */
enum class MaintenanceRequirement {
    /**
     * Financial display is wrong until the task finishes (e.g. a migration added parse-fact
     * columns that dashboard rules read). Startup waits for it.
     */
    BLOCKING,

    /**
     * Stored data is already correct to display; the task only refreshes it. Runs as
     * retryable background work, and open screens reload when it completes.
     */
    BACKGROUND,
}

/**
 * Re-parse requirement per Room schema version.
 *
 * Every schema version must be declared here: adding a migration forces a decision about
 * whether existing rows display correctly before the stored RawSms backlog is re-parsed.
 * Undeclared versions are treated as [MaintenanceRequirement.BLOCKING].
 */
object SchemaFactsBackfillPolicy {
    val requirementBySchemaVersion: Map<Int, MaintenanceRequirement> = buildMap {
        // Registries, transactions, reviews, FX columns, registry metadata/ids: no parse facts.
        (1..9).forEach { put(it, MaintenanceRequirement.BACKGROUND) }
        // cardSmsChannel, paymentDueDate, exchangeRate, international fee, labeled foreign amount.
        put(10, MaintenanceRequirement.BLOCKING)
        // loanType, debitSourceAccountLast4, salaryIncomeWording.
        put(11, MaintenanceRequirement.BLOCKING)
        // Commitment table and pause history only.
        (12..14).forEach { put(it, MaintenanceRequirement.BACKGROUND) }
    }

    /**
     * Strictest requirement among versions in (`lastReparsedVersion`, `currentVersion`], or
     * null when the backlog was already re-parsed for [currentVersion].
     */
    fun requirementFor(lastReparsedVersion: Int, currentVersion: Int): MaintenanceRequirement? {
        if (currentVersion <= lastReparsedVersion) return null
        val pending = (lastReparsedVersion + 1)..currentVersion
        return if (pending.any { requirementBySchemaVersion[it] != MaintenanceRequirement.BACKGROUND }) {
            MaintenanceRequirement.BLOCKING
        } else {
            MaintenanceRequirement.BACKGROUND
        }
    }
}
