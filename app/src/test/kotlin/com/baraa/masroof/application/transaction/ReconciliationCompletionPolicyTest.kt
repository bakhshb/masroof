package com.baraa.masroof.application.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconciliationCompletionPolicyTest {
    @Test
    fun failedCountOfZero_isComplete_includingPendingMatchAndNonFinancial() {
        val report = report(
            failed = 0,
            pendingMatch = 2,
            ignored = 3,
            assembledSingle = 1,
        )

        assertTrue(ReconciliationCompletionPolicy.isComplete(report))
        assertNull(
            ReconciliationCompletionPolicy.incompleteOrNull(
                report = report,
                maskedRawSmsId = "…abcd",
            ),
        )
    }

    @Test
    fun nonzeroFailedCount_isIncomplete_evenWhenOtherRowsPosted() {
        val report = report(failed = 1, assembledSingle = 4, pendingMatch = 1, ignored = 2)

        assertFalse(ReconciliationCompletionPolicy.isComplete(report))
        val incomplete = ReconciliationCompletionPolicy.incompleteOrNull(
            report = report,
            maskedRawSmsId = "…ab12",
        )!!

        assertEquals(1, incomplete.failureCount)
        assertEquals("…ab12", incomplete.maskedRawSmsId)
        assertEquals(ReconciliationCompletionPolicy.STAGE_RECONCILIATION, incomplete.stage)
        assertEquals("reconciliation_incomplete", incomplete.message)
        assertFalse(incomplete.message!!.contains("ab12"))
    }

    @Test
    fun severalFailures_doNotCollapseToSuccess() {
        val report = report(failed = 3, assembledSingle = 1)

        assertFalse(ReconciliationCompletionPolicy.isComplete(report))
        assertEquals(3, ReconciliationCompletionPolicy.incompleteOrNull(report, "…zzzz")!!.failureCount)
    }

    private fun report(
        failed: Int,
        pendingMatch: Int = 0,
        ignored: Int = 0,
        assembledSingle: Int = 0,
    ) = ReconciliationReport(
        summary = ReconciliationSummary(
            assembledSingle = assembledSingle,
            pendingMatch = pendingMatch,
            ignored = ignored,
            failed = failed,
        ),
        reviewCandidates = emptyList(),
        settledRawSmsIds = emptySet(),
    )
}
