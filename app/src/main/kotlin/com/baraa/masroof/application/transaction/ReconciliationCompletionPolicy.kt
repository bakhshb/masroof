package com.baraa.masroof.application.transaction

/**
 * The one application reading of whether a reconciliation pass finished.
 *
 * [ReconciliationSummary.failed] counts required writes and conflicts that did not
 * persist. A pending match or an intentional non-financial disposition is not a
 * failure. A stored ParsedEvent is not a posted ledger row: callers must not treat
 * a returned report as success while [ReconciliationSummary.failed] is nonzero.
 * A thrown exception is also incomplete; this policy only interprets a report
 * that was actually returned.
 */
object ReconciliationCompletionPolicy {
    fun isComplete(report: ReconciliationReport): Boolean = report.summary.failed == 0

    /**
     * Nonthrowing incomplete result for [report], or null when the pass is complete.
     * [maskedRawSmsId] must already be masked; this type never stores a raw identifier
     * or message text.
     */
    fun incompleteOrNull(
        report: ReconciliationReport,
        maskedRawSmsId: String,
        stage: String = STAGE_RECONCILIATION,
    ): ReconciliationIncompleteException? {
        if (isComplete(report)) return null
        return ReconciliationIncompleteException(
            failureCount = report.summary.failed,
            maskedRawSmsId = maskedRawSmsId,
            stage = stage,
        )
    }

    const val STAGE_RECONCILIATION = "reconciliation"
    const val STAGE_REVIEW_UPDATE = "review_update"
}

/**
 * Reconciliation returned, but at least one required write or conflict did not finish.
 *
 * The exception message is a stable code with no SMS body, account number, or raw id.
 * Logs read [failureCount], [maskedRawSmsId], and [stage] instead of [message].
 */
class ReconciliationIncompleteException(
    val failureCount: Int,
    val maskedRawSmsId: String,
    val stage: String = ReconciliationCompletionPolicy.STAGE_RECONCILIATION,
) : Exception("reconciliation_incomplete")
