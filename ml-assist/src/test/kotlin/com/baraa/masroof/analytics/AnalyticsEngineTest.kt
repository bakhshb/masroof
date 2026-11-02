package com.baraa.masroof.analytics

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AnalyticsEngineTest {
    private val start = LocalDate.of(2026, 1, 1).toEpochDay()
    private fun clean(rows: List<FinancialFact>, days: Int = 160, covered: Long? = start, gaps: Set<Long> = emptySet()) =
        FinancialCleaner.clean(rows, start, start + days, covered, gaps) { ((it - start) % 30).toInt() }
    @Test fun duplicatesUseIdentityAndDoNotCollapseTwoRealEqualPayments() {
        val one = FinancialFact("a", start, 500, false, true)
        val data = clean(listOf(one, one, one.copy(id = "b")))
        assertEquals(1000, data.days.first().gross)
        assertEquals(listOf("a"), data.quality.excluded["DUPLICATE_ID"])
    }
    @Test fun refundsUnknownFxFutureAndScopeAreExplicitAndFinancialOutliersStay() {
        val rows = (0..9).map { FinancialFact("p$it", start + it, 100, false, true) } + listOf(
            FinancialFact("large", start + 10, 1_000_000, false, true), FinancialFact("refund", start + 10, 300, true, true),
            FinancialFact("fx", start, null, false, true), FinancialFact("self", start, 100, false, false),
            FinancialFact("future", start + 200, 100, false, true))
        val data = clean(rows)
        assertEquals(999700, data.days[10].net)
        assertEquals(listOf("large"), data.quality.unusualIds)
        assertTrue(data.quality.excluded.keys.containsAll(listOf("MISSING_VALID_AMOUNT_OR_FX", "OUTSIDE_PURCHASE_SCOPE", "FUTURE_DATE")))
    }
    @Test fun missingCoverageIsNotZeroHistoryAndPreventsTraining() {
        val data = clean(emptyList(), covered = null)
        assertTrue(data.days.none { it.covered })
        assertEquals("INSUFFICIENT_DATA", PurchaseForecaster.forecast(data, start + 150, start + 180, start + 160, 5, { 0 }).status)
    }
    @Test fun knownGapIsExcludedFromCoveredDays() {
        val data = clean(emptyList(), gaps = setOf(start + 10))
        assertFalse(data.days[10].covered); assertEquals(160, data.quality.coveredDays)
    }
    @Test fun weekdayBaselineAndPartialTodayNeverRecountTodaysActual() {
        val rows = (0..89).map { FinancialFact("p$it", start + it, 10000, false, true) }
        val data = clean(rows, 89)
        val result = PurchaseForecaster.forecast(data, start + 60, start + 90, start + 89, 2, { 0 })
        assertEquals(300000, result.actual); assertEquals(0L, result.remaining); assertEquals(300000L, result.total)
    }
    @Test fun robustModelHasFiniteWeightsAndNeverClaimsUnvalidatedAccuracy() {
        val rows = (0..160).map { FinancialFact("p$it", start + it, if (it == 110) 10000000 else (1000 + it * 20L), false, true) }
        val data = clean(rows)
        val result = PurchaseForecaster.forecast(data, start + 150, start + 180, start + 160, 5, { ((it - start) % 30).toInt() })
        assertNotNull(result.model); assertTrue(result.model!!.weights.all { it.isFinite() })
        assertTrue(result.remaining!! >= 0)
        if (result.model.accepted) assertTrue(result.model.modelMae <= result.model.baselineMae * .9)
    }
    @Test fun monthlyCadenceUsesCalendarAndAmountChangeNeedsThreePriorPayments() {
        val rows = listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30").mapIndexed { i, date ->
            FinancialFact("s$i", LocalDate.parse(date).toEpochDay(), if (i == 3) 4500 else 3500, false, true, "Service", true)
        }
        val p = RecurringDetector.detect(rows, LocalDate.of(2026, 5, 1).toEpochDay()).single()
        assertEquals(LocalDate.of(2026, 5, 31).toEpochDay(), p.nextDay); assertTrue(p.amountIncreased)
        assertEquals(3500L, p.referenceAmount)
    }
    @Test fun dailyMerchantIsNotInventedAsMonthlyCommitment() {
        val rows = (0..20).map { FinancialFact("p$it", start + it, 3500, false, true, "Grocer", true) }
        assertTrue(RecurringDetector.detect(rows, start + 22).isEmpty())
    }
    @Test fun oldDiscontinuedPaymentsDoNotBecomeNewDueDatesOrAmountChangeAlerts() {
        val rows = listOf(0L, 7L, 14L, 21L).mapIndexed { i, offset -> FinancialFact("old$i", start + offset,
            if (i == 3) 5000 else 3000, false, true, "Old subscription", true) }
        assertTrue(RecurringDetector.detect(rows, start + 90).isEmpty())
    }
    @Test fun correctionAndDeletionChangeFingerprint() {
        val row = FinancialFact("a", start, 100, false, true)
        assertNotEquals(clean(listOf(row)).fingerprint, clean(listOf(row.copy(amount = 200))).fingerprint)
        assertNotEquals(clean(listOf(row)).fingerprint, clean(emptyList()).fingerprint)
    }
    @Test fun unchangedTrainingPrefixIsReusedForSevenDaysThenReevaluated() {
        fun rows(n: Int) = (0 until n).map { FinancialFact("p$it", start + it, 1000 + it * 20L, false, true) }
        val first = PurchaseForecaster.forecast(clean(rows(160), 160), start + 150, start + 180, start + 160, 5, { 0 }).model!!
        val withinWeek = PurchaseForecaster.forecast(clean(rows(166), 166), start + 150, start + 180, start + 166, 5, { 0 }, first).model!!
        assertEquals(first.trainedThrough, withinWeek.trainedThrough)
        val nextWeek = PurchaseForecaster.forecast(clean(rows(167), 167), start + 150, start + 180, start + 167, 5, { 0 }, first).model!!
        assertTrue(nextWeek.trainedThrough > first.trainedThrough)
    }
    @Test fun todaysLargePaymentNeverChangesTheTrainingPrefixOrDeletesActualSpending() {
        val rows = (0 until 160).map { FinancialFact("p$it", start + it, 2000, false, true) }
        val first = PurchaseForecaster.forecast(clean(rows, 160), start + 150, start + 180, start + 160, 5, { 0 })
        val changed = PurchaseForecaster.forecast(clean(rows + FinancialFact("today", start + 160, 900000, false, true), 160), start + 150, start + 180, start + 160, 5, { 0 })
        assertEquals(first.model!!.weights, changed.model!!.weights)
        assertEquals(first.actual + 900000, changed.actual)
    }
}
