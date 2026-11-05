package com.baraa.masroof.integration.analytics

import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.data.analytics.*
import com.baraa.masroof.analytics.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

/** Recurring payments come from the rules; only the user's explicit decision changes a series. */
class RecurringDecisionsTest {
    private val facts = (0..3).map { FinancialFact("payment-$it", it * 7L, 1000, false, true, "Service", true, "BANK:account:1234") }
    private fun feedback(kind: String, label: String?): RecurringFeedbackRow {
        val series = RecurringDetector.series(facts).single()
        val payload = RecurringFeedbackPayload(series.key, series.rows.map { it.id }, label)
        return RecurringFeedbackRow(series.key, kind, AnalyticsJson.format.encodeToString(payload), 100)
    }

    @Test fun rulesFindTheSeriesAndNotRecurringHidesIt() {
        assertEquals("WEEKLY", RecurringDecisionWorkflow.build(facts, emptyList(), 22).single().cadence)
        assertTrue(RecurringDecisionWorkflow.build(facts, listOf(feedback("NOT_RECURRING", "NONE")), 22).isEmpty())
        // Dismissing a suggestion is not a statement about the series.
        assertEquals(1, RecurringDecisionWorkflow.build(facts, listOf(feedback("DISMISSED", null)), 22).size)
    }

    @Test fun aPendingSaveOrAnotherAccountsDecisionChangesNothing() {
        assertEquals("WEEKLY", RecurringDecisionWorkflow.build(facts, listOf(feedback("CONFIRM_PENDING", "MONTHLY")), 22).single().cadence)
        val other = facts.map { it.copy(recurringScope = "OTHER_BANK:account:1234") }
        assertEquals(1, RecurringDecisionWorkflow.build(other, listOf(feedback("NOT_RECURRING", "NONE")), 22).size)
    }

    @Test fun aConfirmedCadenceCountsOnlyWhileTheSavedCommitmentKeepsIt() {
        val row = feedback("CONFIRMED", "MONTHLY")
        assertEquals("MONTHLY", RecurringDecisionWorkflow.build(facts, listOf(row), 22, mapOf("payment-3" to "MONTHLY")).single().cadence)
        assertEquals("WEEKLY", RecurringDecisionWorkflow.build(facts, listOf(row), 22).single().cadence)
        assertEquals("WEEKLY", RecurringDecisionWorkflow.build(facts, listOf(row), 22, mapOf("payment-3" to "WEEKLY")).single().cadence)
    }

    @Test fun aStoredPayloadFromTheOldModelStillReads() {
        val stored = """{"seriesKey":"k","sourceIds":["a","b"],"label":"NONE","featureVersion":"recurring-numeric-v1"}"""
        AnalyticsJson.format.decodeFromString<RecurringFeedbackPayload>(stored).validate()
    }
}
