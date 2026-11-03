package com.baraa.masroof.integration.analytics

import com.baraa.masroof.presentation.navigation.AnalyticsNavigation

import com.baraa.masroof.analytics.*
import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.data.analytics.*
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AnalyticsReadModelsTest {
    @Test fun previousSalaryPeriodsExcludeCurrentAndRespectWeekendBoundariesRefundsAndZeroDays() {
        val current = FinancialPeriodPolicy.periodContaining(LocalDate.of(2026, 10, 21))
        val start = current.startDate.toEpochDay()
        val days = (start - 220..start + 3).map { PurchaseDay(it, if (it == start) 999999 else 100, 20, 1, 0, true) }
        val history = purchasePeriodHistory(clean(days), start)
        assertEquals(6, history.size)
        assertEquals(start, history.last().end)
        history.forEach { period ->
            assertEquals(FinancialPeriodPolicy.periodContaining(LocalDate.ofEpochDay(period.start)).endDateExclusive.toEpochDay(), period.end)
            assertEquals(period.totalDays * 80L, period.actual)
            assertTrue(period.complete)
        }
        val view = AnalysisUiState(purchasePeriods = history).purchaseHistory(3)
        assertEquals(history.takeLast(3).sumOf { it.actual!! }, view.total)
        assertEquals(3, view.available)
        assertEquals(3, view.chartPeriods.size)
        assertFalse(history.any { start in it.start until it.end })
    }
    @Test fun unavailableIsNotZeroAndPartialPeriodsBreakChartContinuity() {
        val current = FinancialPeriodPolicy.periodContaining(LocalDate.of(2026, 10, 21))
        val start = current.startDate.toEpochDay()
        val previous = FinancialPeriodPolicy.previous(current)
        val prior = FinancialPeriodPolicy.previous(previous)
        val days = (prior.startDate.toEpochDay() until start).map { day ->
            PurchaseDay(day, 0, 0, 0, 0, day != prior.startDate.toEpochDay())
        }
        val history = purchasePeriodHistory(clean(days), start)
        assertNull(history.first().actual)
        assertEquals(0L, history.last().actual)
        assertTrue(history.last().complete)
        assertFalse(history[history.lastIndex - 1].complete)
        val view = AnalysisUiState(purchasePeriods = history).purchaseHistory(6)
        assertEquals(2, view.available)
        assertEquals(0L, view.total)
        assertEquals(listOf(history.last()), view.chartPeriods)
    }
    @Test fun negativeRefundTotalsRemainNegativeInHistory() {
        val current = FinancialPeriodPolicy.periodContaining(LocalDate.of(2026, 10, 21))
        val previous = FinancialPeriodPolicy.previous(current)
        val days = (previous.startDate.toEpochDay() until current.startDate.toEpochDay()).map { PurchaseDay(it, 0, 500, 1, 0, true) }
        assertTrue(purchasePeriodHistory(clean(days), current.startDate.toEpochDay()).last().actual!! < 0)
    }
    @Test fun unifiedListFiltersSnoozedAndResolvedButKeepsReadRowsAndStableOrdering() {
        val rows = listOf(AnalysisAlertRow("read", "DUE", "", 1, 1, 20, read = true),
            AnalysisAlertRow("new", "ACTUAL", "", 1, 1, 30),
            AnalysisAlertRow("snoozed", "DUE", "", 1, 1, 40, snoozedUntil = 101),
            AnalysisAlertRow("ended", "DUE", "", 1, 1, 50, resolved = true))
        val state = AnalysisUiState(alerts = rows)
        val visible = visibleAnalysisAlerts(state, 100)
        assertEquals(listOf("new", "read"), visible.map { it.id })
        assertEquals(1, visible.count { !it.read })
        assertEquals(listOf("snoozed", "new", "read"), visibleAnalysisAlerts(state, 101).map { it.id })
    }
    @Test fun dueAlertExposesItsDueDayFromThePolicyKey() {
        assertEquals(20398L, AnalysisAlertRow("due:commitment:abc:20398", "DUE", "Google", 4999, 1, 1).dueEpochDay())
        assertNull(AnalysisAlertRow("payment:key:20398", "PAYMENT", "", 1, 1, 1).dueEpochDay())
    }
    @Test fun arabicAndPersianDigitsAndDecimalSeparatorNormalizeWithoutSilentlyFixingInvalidInput() {
        assertEquals("1200.50", normalizeAnalysisNumber(" ١٢٠٠٫٥٠ "))
        assertEquals("30", normalizeAnalysisNumber("۳۰"))
        assertEquals("1x", normalizeAnalysisNumber("١x"))
        assertNull(normalizeAnalysisNumber("٣٫٥").toIntOrNull())
    }
    @Test fun oldSnapshotDecodesWithoutNewPresentationMetadata() {
        val raw = """{"periodStart":0,"periodEnd":30,"issuedAt":1,"sourceHash":"a","status":"INSUFFICIENT_DATA","method":"NONE","actual":0,"remaining":null,"total":null,"lower":null,"upper":null,"days":[],"quality":{"used":0,"excluded":{},"unusual":[],"coveredDays":0,"coverageKnown":false,"pendingReviews":0,"blocked":false},"candidates":[],"dues":[],"completedPeriods":0}"""
        val snapshot = AnalyticsJson.format.decodeFromString(AnalysisSnapshot.serializer(), raw)
        assertNull(snapshot.trainingFrom); assertNull(snapshot.trainingThrough)
        assertNull(snapshot.insights); assertNull(snapshot.evaluationVersion); assertTrue(snapshot.evaluations.isEmpty())
        assertTrue(snapshot.quality.reasons.isEmpty()); assertNull(snapshot.wholeDaysLower)
    }
    @Test fun averagesExcludePartialPeriodsAndChangesNeverCrossAGap() {
        val periods = listOf(AnalysisPeriod(0, 30, 3000, 30, 30), AnalysisPeriod(30, 60, 9999, 10, 30),
            AnalysisPeriod(60, 90, 6000, 30, 30))
        val view = AnalysisUiState(purchasePeriods = periods).purchaseHistory(3)
        assertEquals(2, view.completeCount); assertEquals(4500L, view.average); assertNull(view.latestChange)
        val next = AnalysisUiState(purchasePeriods = periods + AnalysisPeriod(90, 120, 4500, 30, 30)).purchaseHistory(3)
        assertEquals(-1500L, next.latestChange); assertEquals(5250L, next.average)
    }
    @Test fun publishedHistoryGroupsOnlyConsecutiveEquivalentResultsAndPreservesEveryTimestamp() {
        val base = AnalysisSnapshot(0, 30, 1, "a", "READY", "WEEKDAY_MEDIAN", 100, 200, 300, null, null,
            emptyList(), AnalysisQuality(1, emptyMap(), emptyList(), 90, true, 0, false), emptyList(), emptyList(), 2)
        val source = listOf(base.copy(issuedAt = 4), base.copy(issuedAt = 3, sourceHash = "b"),
            base.copy(issuedAt = 2, total = 400), base)
        val state = AnalysisUiState(history = source)
        val groups = state.forecastHistoryGroups()
        assertEquals(listOf(2, 1, 1), groups.map { it.snapshots.size })
        assertEquals(source, groups.flatMap { it.snapshots })
        assertEquals(source, state.history)
    }
    @Test fun completedPeriodsAtAnEvaluationOriginIgnoreLaterHistoryAndCoverage() {
        val first = FinancialPeriodPolicy.periodContaining(LocalDate.of(2026, 5, 1))
        val second = FinancialPeriodPolicy.next(first)
        val third = FinancialPeriodPolicy.next(second)
        val days = (first.startDate.toEpochDay() until third.endDateExclusive.toEpochDay()).map { PurchaseDay(it, 100, 0, 1, 0, true) }
        assertEquals(1, completedAnalysisPeriods(clean(days), second.startDate.toEpochDay()))
        assertEquals(2, completedAnalysisPeriods(clean(days), third.startDate.toEpochDay()))
        assertEquals(1, completedAnalysisPeriods(clean(days.map { if (it.day == first.startDate.toEpochDay()) it.copy(covered = false) else it }), third.startDate.toEpochDay()))
    }
    @Test fun analyticsNavigationReturnsToCenterOnlyForCenterOrigins() {
        val before = AnalyticsNavigation.centerReturns.value
        AnalyticsNavigation.open("due:test", fromCenter = true)
        AnalyticsNavigation.clear()
        assertNull(AnalyticsNavigation.target.value)
        AnalyticsNavigation.finish()
        assertEquals(before + 1, AnalyticsNavigation.centerReturns.value)
        AnalyticsNavigation.open("phone-alert")
        AnalyticsNavigation.clear(); AnalyticsNavigation.finish()
        assertEquals(before + 1, AnalyticsNavigation.centerReturns.value)
    }
    private fun clean(days: List<PurchaseDay>) = CleanData(days, emptyList(), QualityReport(0, emptyMap(), emptyList(), days.count { it.covered }, true), "test")
}
