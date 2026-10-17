package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.DashboardProduction
import com.baraa.masroof.lab.finance.value.Currency
import com.baraa.masroof.lab.finance.value.Environment
import com.baraa.masroof.lab.finance.value.Money
import java.math.BigDecimal
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class BetaDashboardVisualMapperTest {
    private val from = Instant.parse("2026-10-01T00:00:00Z")
    private val until = Instant.parse("2026-11-01T00:00:00Z")
    private val query = DashboardQuery(Environment.BETA, from, until, ZoneId.of("UTC"), Currency.SAR)
    private fun read(totals: PortfolioTotals?) = DashboardProduction(
        DashboardSnapshot(query, emptyList(), emptyList(), emptyList(), totals = totals), emptySet(), null)

    @Test fun missingEvidenceIsUnavailableAndSignedKnownZeroSurvives() {
        val empty = BetaDashboardVisualMapper.hero(read(null), "Net", "In", "Out", "unavailable")
        assertEquals("unavailable", empty.remaining); assertEquals("unavailable", empty.inflow)
        val totals = PortfolioTotals(Currency.SAR, Money.of(BigDecimal.ZERO, Currency.SAR), null,
            SignedAmount(BigDecimal("-1234.50"), Currency.SAR), null, null, null, null, null, null)
        val mapped = BetaDashboardVisualMapper.hero(read(totals), "Net", "In", "Out", "unavailable")
        assertEquals("-1,234.50 SAR", mapped.remaining)
        assertEquals("0 SAR", mapped.inflow); assertEquals("unavailable", mapped.outflow)
        val arabic = BetaDashboardVisualMapper.hero(read(totals), "Net", "In", "Out", "unavailable", "ar")
        // Java locale data can retain a directional mark before the negative sign.
        // Exact pre-extraction formatting parity (including marks) is tested in app.
        assertEquals("-1,234.50 ر.س", arabic.remaining.filterNot { it == '\u200e' || it == '\u061c' })
    }

    @Test fun trendWithGapIsNotRenderedAsZeroAndKnownNegativeKeepsItsDate() {
        val period = DisplayPeriod(from, until, query.zone, PeriodKind.SELECTED, null)
        val day = DailySpending(LocalDate.parse("2026-10-01"), null, null, null, emptySet())
        val trend = SpendingTrend(null, null, period, Currency.SAR, listOf(day), null)
        assertNull(BetaDashboardVisualMapper.trend(trend))
        assertNull(BetaDashboardVisualMapper.trend(trend.copy(points = emptyList())))
        val known = trend.copy(points = listOf(day.copy(net = SignedAmount(BigDecimal("-20.25"), Currency.SAR))))
        val mapped = checkNotNull(BetaDashboardVisualMapper.trend(known))
        assertEquals(listOf(BigDecimal("-20.25")), mapped.values)
        assertEquals(listOf("2026-10-01"), mapped.dates); assertNull(mapped.average)
    }
}
