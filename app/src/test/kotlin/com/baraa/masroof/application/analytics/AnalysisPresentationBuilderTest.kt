package com.baraa.masroof.application.analytics

import com.baraa.masroof.data.analytics.AnalysisPreferencesRow
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AnalysisPresentationBuilderTest {
    private val start = LocalDate.of(2026, 9, 27).toEpochDay()
    private val zone = ZoneId.of("Asia/Riyadh")
    private fun state(enabledDay: Long) = AnalysisUiState(snapshot = AnalysisSnapshot(start, start + 30, 0, "", "READY", "WEEKDAY_MEDIAN",
        100, 20, 120, null, null, emptyList(), AnalysisQuality(1, emptyMap(), emptyList(), 89, true, 0, false), emptyList(), emptyList(), 2),
        preferences = AnalysisPreferencesRow(enabledAt = LocalDate.ofEpochDay(enabledDay).atStartOfDay(zone).toInstant().toEpochMilli()))
    @Test fun startingAfterDayEightStillAllowsTheFirstEligibleLiveForecast() {
        val result = AnalysisPresentationBuilder.comparison(state(start + 25), start + 26, zone)
        assertEquals(ForecastComparisonStatus.AWAITING_ELIGIBLE_FORECAST, result.status)
        assertEquals(start + 7, result.anchorDay)
        assertEquals(start + 25, result.enabledDay)
    }
    @Test fun passingDayEightWithoutAQualifiedForecastNeverKeepsSayingWaitForDayEight() {
        assertEquals(ForecastComparisonStatus.AWAITING_ELIGIBLE_FORECAST,
            AnalysisPresentationBuilder.comparison(state(start), start + 12, zone).status)
        assertEquals(ForecastComparisonStatus.NO_QUALIFIED_FORECAST,
            AnalysisPresentationBuilder.comparison(state(start), start + 30, zone).status)
        assertEquals(ForecastComparisonStatus.WAITING_FOR_ANCHOR,
            AnalysisPresentationBuilder.comparison(state(start), start + 3, zone).status)
    }
    @Test fun latePublishedAndLegacyComparisonsRetainTheirLifecycleAndEligibility() {
        val pair = com.baraa.masroof.data.analytics.ForecastComparisonRow("late", start, start + 30, 1,
            "a".repeat(64), com.baraa.masroof.analytics.AnalyticsVersion.MODEL, ForecastPerformancePolicy.VERSION,
            120, 130, originDay = start + 25)
        val published = state(start + 25).copy(forecastComparisons = listOf(pair))
        assertEquals(ForecastComparisonStatus.WAITING_FOR_PERIOD, AnalysisPresentationBuilder.comparison(published, start + 26, zone).status)
        assertEquals(ForecastComparisonStatus.INCOMPLETE_COVERAGE, AnalysisPresentationBuilder.comparison(published, start + 30, zone).status)
        assertEquals(ForecastComparisonStatus.READY, AnalysisPresentationBuilder.comparison(published.copy(
            forecastComparisons = listOf(pair.copy(actual = 125))), start + 30, zone).status)
        val legacy = pair.copy(evaluationVersion = ForecastPerformancePolicy.LEGACY_VERSION, originDay = null)
        assertEquals(ForecastComparisonStatus.WAITING_FOR_PERIOD, AnalysisPresentationBuilder.comparison(published.copy(
            forecastComparisons = listOf(legacy)), start + 26, zone).status)
        assertEquals(ForecastComparisonStatus.AWAITING_ELIGIBLE_FORECAST, AnalysisPresentationBuilder.comparison(published.copy(
            forecastComparisons = listOf(pair.copy(modelVersion = "unsupported"))), start + 26, zone).status)
    }
    @Test fun knownCoverageGapsAreNotRenderedAsZeroSpendingDays() {
        val snapshot = state(start).snapshot!!.copy(days = listOf(AnalysisDay(start, 0, null)),
            quality = state(start).snapshot!!.quality.copy(coverageDays = listOf(AnalysisCoverageDay(start, false))))
        assertNull(AnalysisPresentationBuilder.dailyBars(snapshot).single().amount)
    }
    @Test fun forecastExplanationNeverClaimsAPersonalModelFromReadinessOrBlockedData() {
        val snapshot = state(start).snapshot!!
        assertEquals(ForecastMethod.INITIAL, AnalysisPresentationBuilder.forecast(snapshot).method)
        assertEquals(ForecastMethod.PERSONAL, AnalysisPresentationBuilder.forecast(snapshot.copy(
            method = com.baraa.masroof.analytics.AnalyticsVersion.MODEL)).method)
        assertEquals(ForecastMethod.UNAVAILABLE, AnalysisPresentationBuilder.forecast(snapshot.copy(
            quality = snapshot.quality.copy(blocked = true))).method)
        assertEquals(ForecastMethod.UNAVAILABLE, AnalysisPresentationBuilder.forecast(snapshot.copy(total = null)).method)
        assertEquals(ForecastMethod.UNAVAILABLE, AnalysisPresentationBuilder.forecast(snapshot.copy(method = "future-model")).method)
    }
    @Test fun onlyCurrentQualifiedHorizonChecksMayExplainForecastError() {
        val snapshot = state(start).snapshot!!.copy(evaluationVersion = com.baraa.masroof.analytics.AnalyticsVersion.EVALUATION,
            evaluations = listOf(AnalysisEvaluation(7, 8, 100, -20), AnalysisEvaluation(14, 7, 20, 0),
                AnalysisEvaluation(28, 8, -1, 0), AnalysisEvaluation(3, 20, 0, 0)))
        assertEquals(listOf(7), AnalysisPresentationBuilder.forecast(snapshot).evaluations.map { it.horizon })
        assertTrue(AnalysisPresentationBuilder.forecast(snapshot.copy(evaluationVersion = null)).evaluations.isEmpty())
        assertTrue(AnalysisPresentationBuilder.forecast(snapshot.copy(status = "INSUFFICIENT_DATA")).evaluations.isEmpty())
    }
    @Test fun chartSeparatesTodaysKnownActualFromAdditionalEstimatedSpendingAndKeepsRefundsSigned() {
        val snapshot = state(start).snapshot!!.copy(days = listOf(AnalysisDay(start, -100, null),
            AnalysisDay(start + 1, 150, null), AnalysisDay(start + 1, null, 150), AnalysisDay(start + 2, null, 50)))
        val points = AnalysisPresentationBuilder.dailyBars(snapshot)
        assertEquals(listOf(false, false, true), points.map { it.expected })
        assertEquals(-100L, points.first().amount)
        assertEquals(150L, points[1].amount)
        val withAdditionalToday = snapshot.copy(days = snapshot.days + AnalysisDay(start + 1, null, 200))
        assertTrue(AnalysisPresentationBuilder.dailyBars(withAdditionalToday)[1].expected)
    }
    @Test fun commitmentsTotalOnlyConfirmedDuesAndCountDaysFromToday() {
        val today = start + 8
        val dues = listOf(AnalysisDue("electricity", "SEC", 43850, today + 1, true), AnalysisDue("stc", "STC", 17635, today + 1, true),
            AnalysisDue("guess", "Gym", 9000, today + 3, false), AnalysisDue("late", "Loan", 1000, today - 2, true),
            AnalysisDue("undated", "Other", 0, null, true))
        val view = AnalysisPresentationBuilder.commitments(state(start).snapshot!!.copy(dues = dues), today)
        assertEquals(62485L, view.confirmedTotal)
        assertEquals(4, view.confirmedCount)
        assertEquals(listOf(1L, 1L, 3L, -2L, null), view.rows.map { it.daysUntil })
        assertEquals(0f, view.rows[2].share) // an estimate is never part of the confirmed total
        assertEquals(43850f / 62485, view.rows[0].share, 1e-6f)
        assertEquals(start + 29 - today, view.daysToPeriodEnd)
    }
    @Test fun compositionSharesPositivePartsOnlyAndRoundsHalfEven() {
        val groups = listOf(com.baraa.masroof.application.dashboard.AnalysisSpendingGroup("PURCHASES", 332237, emptyList()),
            com.baraa.masroof.application.dashboard.AnalysisSpendingGroup("CASH", 245000, emptyList()),
            com.baraa.masroof.application.dashboard.AnalysisSpendingGroup("TRANSFERS", 643733, emptyList()),
            com.baraa.masroof.application.dashboard.AnalysisSpendingGroup("REFUNDS", -5000, emptyList()))
        val snapshot = state(start).snapshot!!.copy(insights = com.baraa.masroof.application.dashboard.AnalysisSpendingInsights(groups, emptyList(), null))
        val parts = AnalysisPresentationBuilder.composition(snapshot)
        assertEquals(listOf("PURCHASES", "CASH", "TRANSFERS"), parts.map { it.group.key })
        assertEquals(listOf(27, 20, 53), parts.map { it.percent })
        assertTrue(AnalysisPresentationBuilder.composition(snapshot.copy(insights = null)).isEmpty())
    }
    @Test fun underestimatesOnlyWhenEveryQualifiedHorizonRanLow() {
        val low = listOf(AnalysisEvaluation(1, 13, 119778, 113357), AnalysisEvaluation(7, 11, 429500, 414815))
        assertTrue(ForecastExplanation(ForecastMethod.INITIAL, low).underestimates)
        assertFalse(ForecastExplanation(ForecastMethod.INITIAL, low + AnalysisEvaluation(14, 9, 100, -50)).underestimates)
        assertFalse(ForecastExplanation(ForecastMethod.INITIAL, emptyList()).underestimates)
    }
}
