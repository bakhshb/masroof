package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.value.Currency
import com.baraa.masroof.lab.finance.value.Environment
import com.baraa.masroof.lab.finance.posting.PostingKey
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.*

class BetaDashboardExplorerTest {
    @Test fun comparisonWindowWithOutOfScopeProvenanceCannotClaimAnEmptyTransactionDrilldown() {
        val zone = ZoneId.of("Asia/Riyadh")
        val from = Instant.parse("2026-09-30T21:00:00Z"); val until = Instant.parse("2026-10-31T21:00:00Z")
        val point = DailySpending(LocalDate.of(2026, 10, 1), SignedAmount(BigDecimal.TEN, Currency.SAR), null, null, setOf(PostingKey(Environment.BETA, "outside-selected-read")))
        val trend = SpendingTrend(null, null, DisplayPeriod(from, until, zone, PeriodKind.SELECTED, null), Currency.SAR, listOf(point), null)
        val snapshot = DashboardSnapshot(DashboardQuery(Environment.BETA, from, until, zone, Currency.SAR), emptyList(), emptyList(), emptyList(),
            analysis = AnalysisSummary(Currency.SAR, SummaryMetric.entries.associateWith { SignedAmount(BigDecimal.ZERO, Currency.SAR) }, 0, 0, 0, listOf(trend), emptyList()))
        assertFalse(BetaDashboardExplorer.canDrillDown(snapshot, BetaChartChoice(), point.date))
    }
}
