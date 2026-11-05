package com.baraa.masroof.integration.analytics

import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.data.analytics.*
import com.baraa.masroof.analytics.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class RecurringLearningWorkflowTest {
    private val facts = (0..3).map { FinancialFact("payment-$it", it * 7L, 1000, false, true, "Service", true, "BANK:account:1234") }
    private fun feedback(kind: String, label: String?): RecurringFeedbackRow {
        val series = RecurringDetector.series(facts).single()
        val payload = RecurringFeedbackPayload(series.key, series.rows.map { it.id }, label)
        return RecurringFeedbackRow(series.key, kind, AnalyticsJson.format.encodeToString(payload), 100)
    }
    @Test fun dismissalIsNotANegativeTrainingLabelButExplicitCorrectionIs() {
        val (_, dismissed) = RecurringLearningWorkflow.build(facts, listOf(feedback("DISMISSED", null)), 22)
        assertEquals(0, dismissed.trainingSeries + dismissed.validationSeries)
        val (patterns, corrected) = RecurringLearningWorkflow.build(facts, listOf(feedback("NOT_RECURRING", "NONE")), 22)
        assertTrue(patterns.isEmpty())
        assertEquals(1, corrected.trainingSeries + corrected.validationSeries)
        assertEquals("RULES", corrected.method)
    }
    @Test fun savedHumanCommitmentsTeachWhileUnconfirmedPatternsNeverTeachThemselves() {
        val (_, unconfirmed) = RecurringLearningWorkflow.build(facts, emptyList(), 22)
        assertEquals(0, unconfirmed.trainingSeries + unconfirmed.validationSeries)
        val (_, confirmed) = RecurringLearningWorkflow.build(facts, emptyList(), 22, mapOf("payment-0" to "WEEKLY"))
        assertEquals(1, confirmed.trainingSeries + confirmed.validationSeries)
    }
    @Test fun pendingFinancialSaveAndMissingSourceCannotTeachOrSuppressAnotherAccount() {
        val (_, pending) = RecurringLearningWorkflow.build(facts, listOf(feedback("CONFIRM_PENDING", "WEEKLY")), 22)
        assertEquals(0, pending.trainingSeries + pending.validationSeries)
        val other = facts.map { it.copy(recurringScope = "OTHER_BANK:account:1234") }
        val (patterns, status) = RecurringLearningWorkflow.build(other, listOf(feedback("NOT_RECURRING", "NONE")), 22)
        assertEquals(1, patterns.size)
        assertEquals(0, status.trainingSeries + status.validationSeries)
    }
    @Test fun deletingOrChangingTheSavedCommitmentRevokesItsEarlierTrainingLabel() {
        val row = feedback("CONFIRMED", "WEEKLY")
        val (_, confirmed) = RecurringLearningWorkflow.build(facts, listOf(row), 22, mapOf("payment-3" to "WEEKLY"))
        assertEquals(1, confirmed.trainingSeries + confirmed.validationSeries)
        val (_, deleted) = RecurringLearningWorkflow.build(facts, listOf(row), 22)
        assertEquals(0, deleted.trainingSeries + deleted.validationSeries)
        val (_, changed) = RecurringLearningWorkflow.build(facts, listOf(row), 22, mapOf("payment-3" to "MONTHLY"))
        assertEquals(1, changed.trainingSeries + changed.validationSeries)
    }
    @Test fun missingEvidenceRevokesStatisticalTrainingButNotAnExplicitNegativePreference() {
        val newer = facts.map { it.copy(id = "new-${it.id}") }
        val (patterns, status) = RecurringLearningWorkflow.build(newer, listOf(feedback("NOT_RECURRING", "NONE")), 22)
        assertTrue(patterns.isEmpty())
        assertEquals(0, status.trainingSeries + status.validationSeries)
    }
}
