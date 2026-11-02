package com.baraa.masroof.analytics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AnalyticsEvaluationRegressionTest {
    private val start = LocalDate.of(2026, 1, 1).toEpochDay()
    private fun data(days: Int, amount: (Int) -> Long = { 10000 }, gaps: Set<Long> = emptySet()) =
        FinancialCleaner.clean((0..days).map { FinancialFact("p$it", start + it, amount(it), false, true) },
            start, start + days, start, gaps) { ((it - start) % 30).toInt() }
    private fun forecast(data: CleanData, days: Int, completed: Int = 2) = PurchaseForecaster.forecast(data,
        start + days - 10, start + days + 6, start + days, completed, { ((it - start) % 30).toInt() })

    @Test fun everyCalendarHorizonHasItsOwnSamplesAndIntervalsExcludePartialToday() {
        val result = forecast(data(120), 120)
        assertEquals((1..31).toSet(), result.baseline!!.errors.keys)
        assertTrue(result.baseline.errors.getValue(1).size > result.baseline.errors.getValue(7).size)
        assertEquals(listOf(1, 7, 14, 28), result.evaluations.map { it.horizon })
        assertTrue(result.evaluations.all { it.mae == 0L && it.bias == 0L && it.count >= 8 })
        assertNull(result.lower); assertNull(result.upper)
        assertEquals(50000L, result.wholeDaysLower); assertEquals(50000L, result.wholeDaysUpper)
        val largerToday = forecast(data(120, { if (it == 120) 100000 else 10000 }), 120)
        assertEquals(result.wholeDaysLower, largerToday.wholeDaysLower)
        assertEquals(result.baseline.signedErrors, largerToday.baseline!!.signedErrors)
        assertEquals(result.actual + 90000, largerToday.actual)
    }

    @Test fun gapsNeverBecomeZeroValidationDays() {
        val cleaned = data(100, gaps = setOf(start + 80))
        val result = forecast(cleaned, 100)
        val history = cleaned.days.filter { it.day < start + 100 && it.covered }
        for (h in 1..31) {
            val expected = (56 until history.size step 3).toList().takeLast(40).count { cut ->
                cut + h <= history.size && history[cut + h - 1].day - history[cut].day == h - 1L
            }
            assertEquals(expected, result.baseline!!.errors[h].orEmpty().size, "h=$h")
        }
    }

    @Test fun personalPromotionNeedsEightEvaluationsAtEveryCoreHorizon() {
        val result = forecast(data(140, { 1000 + it * 20L }), 140, 4)
        assertNotNull(result.model)
        assertFalse(result.model!!.accepted)
        assertEquals("NEEDS_EVALUATIONS", result.reason)
        assertTrue(result.model.signedErrors[28].orEmpty().size < 8)
    }

    @Test fun ineligibleHistoricalOriginsDoNotReportAccuracy() {
        val result = PurchaseForecaster.forecast(data(140), start + 130, start + 146, start + 140, 4, { 0 },
            completedPeriodsAt = { if (it < start + 140) 0 else 4 })
        assertTrue(result.evaluations.isEmpty())
        assertNull(result.wholeDaysLower)
        assertFalse(result.model!!.accepted)
    }

    @Test fun intermittentLargePaymentsStayInActualAndExposeUnderestimationBias() {
        val result = forecast(data(119, { if (it % 19 == 0) 100000 else 0 }), 119)
        assertTrue(result.actual > 0)
        assertTrue(result.evaluations.first { it.horizon == 28 }.bias > 0)
        assertTrue(result.evaluations.first { it.horizon == 28 }.mae > 0)
    }

    private fun monthly() = listOf("2026-07-20", "2026-08-20", "2026-09-20").mapIndexed { i, day ->
        FinancialFact("m$i", LocalDate.parse(day).toEpochDay(), 10000, false, true, "Service", true)
    }
    @Test fun oldSameDayPairDoesNotEraseRecentMonthlyPatternOrBecomeEvidence() {
        val old = monthly().first().copy(id = "old1", day = LocalDate.parse("2026-06-20").toEpochDay())
        val result = RecurringDetector.detect(monthly() + old + old.copy(id = "old2"), LocalDate.parse("2026-10-19").toEpochDay()).single()
        assertEquals(monthly().map { it.id }, result.sourceIds)
        assertEquals(LocalDate.parse("2026-10-20").toEpochDay(), result.nextDay)
    }
    @Test fun overdueOccurrenceStaysInItsMonthUntilGraceExpires() {
        assertEquals(LocalDate.parse("2026-10-20").toEpochDay(),
            RecurringDetector.detect(monthly(), LocalDate.parse("2026-10-22").toEpochDay()).single().nextDay)
        assertTrue(RecurringDetector.detect(monthly(), LocalDate.parse("2026-10-26").toEpochDay()).isEmpty())
    }
    @Test fun sameDayPaymentsAreNeverAddedTogetherToInventRecurringEvidence() {
        val rows = monthly().take(2)
        assertTrue(RecurringDetector.detect(rows + rows.last().copy(id = "second"), LocalDate.parse("2026-09-19").toEpochDay()).isEmpty())
    }
    @Test fun missingFxOutsideScopeIsInformationalWhileEligibleMissingFxNeedsAttention() {
        val result = FinancialCleaner.clean(listOf(FinancialFact("bill", start, null, false, false, "Utility", true),
            FinancialFact("purchase", start, null, false, true)), start, start + 100, start) { 0 }
        assertEquals(listOf("bill"), result.quality.excluded["OUTSIDE_PURCHASE_SCOPE"])
        assertEquals(listOf("purchase"), result.quality.excluded["MISSING_VALID_AMOUNT_OR_FX"])
        assertFalse(result.days.first().covered)
        assertTrue(result.recurring.isEmpty())
        val outsideOnly = FinancialCleaner.clean(listOf(FinancialFact("bill", start, null, false, false)), start, start + 100, start) { 0 }
        assertTrue(outsideOnly.days.first().covered)
    }
}
