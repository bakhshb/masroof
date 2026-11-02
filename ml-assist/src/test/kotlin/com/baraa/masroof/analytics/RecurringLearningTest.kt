package com.baraa.masroof.analytics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecurringLearningTest {
    private fun features(label: RecurringLabel) = when (label) {
        RecurringLabel.WEEKLY -> listOf(1.0, .2, 0.0, 0.0, 1.0, 0.0, 1.0, .2)
        RecurringLabel.MONTHLY -> listOf(1.0, 1.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0)
        RecurringLabel.NONE -> listOf(1.0, .5, 1.0, 1.0, 1.0, 1.0, 2.0, 1.0)
    }
    private fun examples(count: Int) = (1..count).map { index ->
        val label = RecurringLabel.entries[index % 3]
        RecurringExample("series-$index", features(label), label, index.toLong())
    }

    @Test fun fittingLearnsNumericalWeightsAndPredictsUnseenSeries() {
        val model = RecurringLearning.fit(examples(90))
        RecurringLabel.entries.forEach { label -> assertEquals(label, model.predict(features(label))?.label) }
        assertTrue(model.weights.flatten().any { it != 0.0 })
        assertFalse(model.accepted) // Fitting alone does not authorize production use.
    }

    @Test fun personalPromotionUsesEntireHeldOutSeriesAndRequiresImprovement() {
        val seed = RecurringModel("seed", List(3) { List(9) { 0.0 } }, accepted = true)
        val rows = examples(150)
        val result = RecurringLearning.personalize(seed, rows, examples(60).map { it.copy(seriesKey = "validation-${it.seriesKey}") })
        assertTrue(result.personalUsed, "reason=${result.reason}")
        assertTrue(result.validationSeries >= 8)
        assertEquals(150, result.trainingSeries + result.validationSeries)
        assertNotEquals(seed.weights, result.model!!.weights)
        val repeated = RecurringLearning.personalize(seed, rows + rows, examples(60))
        assertEquals(result.trainingSeries, repeated.trainingSeries)
        assertEquals(result.model.weights, repeated.model!!.weights)
    }

    @Test fun sparseOrWithdrawnFeedbackNeverPromotesAndIneligibleSeedStaysIneligible() {
        val seed = RecurringLearning.fit(examples(90)).copy(accepted = true)
        assertFalse(RecurringLearning.personalize(seed, examples(2), emptyList()).personalUsed)
        assertFalse(RecurringLearning.personalize(seed, emptyList(), emptyList()).personalUsed)
        assertEquals("SEED_NOT_ELIGIBLE", RecurringLearning.personalize(seed.copy(accepted = false), examples(150), examples(60)).reason)
    }

    @Test fun bankQualifiedSeriesDoNotMergeSameNamedMerchantsAndSameDayPairsStayAmbiguous() {
        val rows = (0..2).flatMap { i -> listOf(
            FinancialFact("a$i", i * 7L, 1000, false, true, "Service", true, "BANK_A:account:1234"),
            FinancialFact("b$i", i * 7L, 1000, false, true, "Service", true, "BANK_B:account:1234")) }
        assertEquals(2, RecurringDetector.series(rows).size)
        assertEquals(2, RecurringDetector.detect(rows, 15).size)
        assertNotEquals(RecurringDetector.detect(rows, 15)[0].key, RecurringDetector.detect(rows, 15)[1].key)
        assertEquals(1, RecurringDetector.series(rows + rows.first().copy(id = "other")).size)
    }
}
