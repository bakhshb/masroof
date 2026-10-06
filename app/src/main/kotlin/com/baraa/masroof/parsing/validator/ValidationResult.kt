package com.baraa.masroof.parsing.validator

/**
 * Aggregated validation outcome for a parse draft/event.
 *
 * [isAcceptableForAutomaticUse] is the automation firewall: a financial draft with any
 * ERROR finding is finalized as REVIEW_REQUIRED, never SUCCESS. Warnings never block.
 */
data class ValidationResult(
    val findings: List<ValidationFinding>,
) {
    val errors: List<ValidationFinding>
        get() = findings.filter { it.severity == ValidationSeverity.ERROR }

    val warnings: List<ValidationFinding>
        get() = findings.filter { it.severity == ValidationSeverity.WARNING }

    val isAcceptableForAutomaticUse: Boolean
        get() = errors.isEmpty()

    /** Human-readable reasons for the review row when automatic use is blocked. */
    val reviewReasons: List<String>
        get() = errors.map { it.message }

    /** Stable codes (e.g. `V-011`) of the findings that block automatic use. */
    val blockingCodes: List<String>
        get() = errors.map { it.code }.distinct()
}
