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
    @Test fun transfersAreActualSpendingButTheEstimateNeitherLearnsFromThemNorPredictsThem() {
        val purchases = (0..160).map { FinancialFact("p$it", start + it, 1000 + it % 7 * 500L, false, true) }
        // A few large transfers, one of them inside the current period.
        val transfers = listOf(20, 75, 155).map { FinancialFact("t$it", start + it, 3_000_000, false, true, regular = false) }
        val phase = { day: Long -> ((day - start) % 30).toInt() }
        val without = PurchaseForecaster.forecast(clean(purchases), start + 150, start + 180, start + 160, 5, phase)
        val with = PurchaseForecaster.forecast(clean(purchases + transfers), start + 150, start + 180, start + 160, 5, phase)
        assertEquals(without.remaining, with.remaining)
        assertEquals(without.points, with.points)
        assertEquals(without.wholeDaysUpper, with.wholeDaysUpper)
        assertEquals(without.actual + 3_000_000, with.actual)
        assertEquals(without.total!! + 3_000_000, with.total)
        val day = clean(purchases + transfers).days[155]
        assertEquals(3_000_000L, day.irregular); assertEquals(day.gross + 3_000_000, day.net)
        // Marking a payment as a transfer is a different input.
        assertNotEquals(clean(purchases + transfers).fingerprint, clean(purchases + transfers.map { it.copy(regular = true) }).fingerprint)
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
    @Test fun sparseSpendingBaselineForecastsTheExpectedTotalNotAZeroMedian() {
        // Spending every third day: each weekday's median is 0, its mean is a third of the purchase.
        val rows = (0 until 90 step 3).map { FinancialFact("p$it", start + it, 9000, false, true) }
        val result = PurchaseForecaster.forecast(clean(rows, 90), start + 60, start + 91, start + 90, 2, { 0 })
        assertEquals("WEEKDAY_MEDIAN", result.method)
        assertEquals(3000.0, result.remaining!!.toDouble(), 600.0)
    }
    @Test fun oneHugeDayIsCappedInTheBaseline() {
        val days = (0 until 84).map { PurchaseDay(start + it, if (it == 40) 10_000_000 else 1000, 0, 1, 0, true) }
        assertTrue(PurchaseForecaster.weekdayBaseline(days).all { it < 2000 })
    }
    @Test fun hijriSeasonsFollowUmmAlQura() {
        assertEquals(PurchaseForecaster.Season.RAMADAN, PurchaseForecaster.season(LocalDate.of(2026, 3, 1).toEpochDay()))
        assertEquals(PurchaseForecaster.Season.EID, PurchaseForecaster.season(LocalDate.of(2026, 3, 20).toEpochDay()))
        assertEquals(PurchaseForecaster.Season.NONE, PurchaseForecaster.season(LocalDate.of(2026, 1, 15).toEpochDay()))
        assertEquals(PurchaseForecaster.Season.NONE, PurchaseForecaster.season(LocalDate.of(1800, 1, 1).toEpochDay()))
    }
    @Test fun modelIsStillReusedAfterTheTrainingWindowStartsMoving() {
        fun rows(n: Int) = (0 until n).map { FinancialFact("p$it", start + it, 1000 + (it % 9) * 300L, false, true) }
        val first = PurchaseForecaster.forecast(clean(rows(400), 400), start + 390, start + 420, start + 400, 12, { 0 }).model!!
        val next = PurchaseForecaster.forecast(clean(rows(401), 401), start + 390, start + 420, start + 401, 12, { 0 }, first).model!!
        assertEquals(first, next)
    }
    @Test fun aSeasonSeenOnFewTrainingDaysIsNotLearned() {
        // Training starts in mid-February 2026, so only the last few days of Ramadan 1447 are fitted rows.
        val from = LocalDate.of(2026, 2, 15).toEpochDay()
        val facts = (0 until 200).map { FinancialFact("p$it", from + it, 1000 + (it % 7) * 400L, false, true) }
        val data = FinancialCleaner.clean(facts, from, from + 200, from) { ((it - from) % 30).toInt() }
        val model = PurchaseForecaster.forecast(data, from + 190, from + 220, from + 200, 6, { ((it - from) % 30).toInt() }).model!!
        assertEquals(0.0, model.weights[13])
    }
    @Test fun oneOrTwoPurchasesAreNeverCappedAwayFromTheBaseline() {
        val one = (0 until 84).map { PurchaseDay(start + it, if (it == 40) 300000 else 0, 0, 1, 0, true) }
        assertTrue(PurchaseForecaster.weekdayBaseline(one).sum() > 0)
        val two = (0 until 84).map { PurchaseDay(start + it, when (it) { 40 -> 300000; 50 -> 1000; else -> 0 }, 0, 1, 0, true) }
        assertEquals(301000.0 / 12, PurchaseForecaster.weekdayBaseline(two).sum(), 1.0)
    }
    private fun payments(vararg dates: String, amounts: List<Long> = List(dates.size) { 3500L }) = dates.mapIndexed { i, date ->
        FinancialFact("r$i", LocalDate.parse(date).toEpochDay(), amounts[i], false, true, "Service", true)
    }
    @Test fun oneMissedMonthKeepsAMonthlySeries() {
        val rows = payments("2026-01-10", "2026-02-10", "2026-04-11", "2026-05-09")
        val pattern = RecurringDetector.detect(rows, LocalDate.of(2026, 5, 20).toEpochDay()).single()
        assertEquals("MONTHLY", pattern.cadence)
        assertEquals(LocalDate.of(2026, 6, 9).toEpochDay(), pattern.nextDay)
        // Every other month, or two gaps, is not monthly.
        assertTrue(RecurringDetector.detect(payments("2026-01-10", "2026-03-10", "2026-05-10"), LocalDate.of(2026, 5, 20).toEpochDay()).isEmpty())
        assertTrue(RecurringDetector.detect(payments("2026-01-10", "2026-03-10", "2026-04-10", "2026-06-10"), LocalDate.of(2026, 6, 20).toEpochDay()).isEmpty())
    }
    @Test fun twoSimilarPaymentsAYearApartAreYearly() {
        val pattern = RecurringDetector.detect(payments("2025-03-01", "2026-03-04", amounts = listOf(90000, 95000)),
            LocalDate.of(2026, 6, 1).toEpochDay()).single()
        assertEquals("YEARLY", pattern.cadence)
        assertEquals(LocalDate.of(2027, 3, 4).toEpochDay(), pattern.nextDay)
        // A very different amount, a different season, or two monthly-looking rows are not yearly.
        assertTrue(RecurringDetector.detect(payments("2025-03-01", "2026-03-04", amounts = listOf(20000, 95000)), LocalDate.of(2026, 6, 1).toEpochDay()).isEmpty())
        assertTrue(RecurringDetector.detect(payments("2025-03-01", "2026-05-20"), LocalDate.of(2026, 6, 1).toEpochDay()).isEmpty())
        // Two shop visits in the same season are not a renewal: ten days apart, or a fifth more.
        assertTrue(RecurringDetector.detect(payments("2025-03-01", "2026-03-11"), LocalDate.of(2026, 6, 1).toEpochDay()).isEmpty())
        assertTrue(RecurringDetector.detect(payments("2025-03-01", "2026-03-02", amounts = listOf(80000, 100000)), LocalDate.of(2026, 6, 1).toEpochDay()).isEmpty())
        assertTrue(RecurringDetector.detect(payments("2026-04-01", "2026-05-01"), LocalDate.of(2026, 5, 10).toEpochDay()).isEmpty())
        // An old yearly series whose renewal never came expires.
        assertTrue(RecurringDetector.detect(payments("2024-03-01", "2025-03-01"), LocalDate.of(2026, 6, 1).toEpochDay()).isEmpty())
    }
}
