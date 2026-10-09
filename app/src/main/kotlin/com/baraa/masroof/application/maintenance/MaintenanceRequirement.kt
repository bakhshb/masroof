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

    /**
     * The schema change does not need a stored-SMS reparse. Record the version and
     * do not schedule or run parse-fact backfill.
     */
    NOT_REQUIRED,
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
        // Commitment table and pause history.
        (12..14).forEach { put(it, MaintenanceRequirement.BACKGROUND) }
        // Processing-retry markers. No parse-fact columns.
        put(15, MaintenanceRequirement.BACKGROUND)
        // Transaction timezone provenance. Existing instants stay valid; null zone is legacy.
        put(16, MaintenanceRequirement.BACKGROUND)
        // Index-only. Existing rows stay correct; do not reparse the SMS backlog.
        put(17, MaintenanceRequirement.NOT_REQUIRED)
        // Provider aliases. Existing RawSms rows stay immutable and already display correctly.
        put(18, MaintenanceRequirement.NOT_REQUIRED)
    }

    /**
     * Strictest requirement among versions in (`lastReparsedVersion`, `currentVersion`], or
     * null when the backlog was already re-parsed for [currentVersion].
     *
     * A range is [MaintenanceRequirement.NOT_REQUIRED] only when every version in it is.
     * Undeclared versions are [MaintenanceRequirement.BLOCKING].
     */
    fun requirementFor(lastReparsedVersion: Int, currentVersion: Int): MaintenanceRequirement? {
        if (currentVersion <= lastReparsedVersion) return null
        val pending = ((lastReparsedVersion + 1)..currentVersion).map { requirementBySchemaVersion[it] }
        return when {
            pending.any { it != MaintenanceRequirement.BACKGROUND && it != MaintenanceRequirement.NOT_REQUIRED } ->
                MaintenanceRequirement.BLOCKING
            pending.any { it == MaintenanceRequirement.BACKGROUND } ->
                MaintenanceRequirement.BACKGROUND
            else -> MaintenanceRequirement.NOT_REQUIRED
        }
    }
}
