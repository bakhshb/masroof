package com.baraa.masroof.application.analytics

import com.baraa.masroof.data.analytics.ForecastComparisonRow
import com.baraa.masroof.analytics.AnalyticsVersion
import java.math.BigDecimal

data class ForecastPerformance(val allowPersonal: Boolean = true, val periods: Int = 0,
    val personalMae: Long? = null, val baselineMae: Long? = null, val pausedAfter: Long? = null,
    val recent: List<ForecastComparisonView> = emptyList())

/** One period's first supported comparison, ready to display; [barScale] is the largest bar on its row. */
data class ForecastComparisonView(val periodStart: Long, val periodEnd: Long, val issuedAt: Long, val originDay: Long?,
    val personalTotal: Long, val baselineTotal: Long, val actual: Long?) {
    val barScale: Long get() = maxOf(personalTotal, baselineTotal, actual ?: 0, 0)
}

object ForecastPerformancePolicy {
    const val VERSION = "published-first-valid-v2"
    const val LEGACY_VERSION = "published-day8-v1"
    fun eligibleOrigin(start: Long, end: Long, day: Long) = day >= start + 7 && day < end
    fun originDay(row: ForecastComparisonRow): Long? = row.originDay
        ?: (row.periodStart + 7).takeIf { row.evaluationVersion == LEGACY_VERSION && it < row.periodEnd }
    fun supports(row: ForecastComparisonRow): Boolean = row.modelVersion == AnalyticsVersion.MODEL &&
        row.evaluationVersion in setOf(VERSION, LEGACY_VERSION) &&
        originDay(row)?.let { eligibleOrigin(row.periodStart, row.periodEnd, it) } == true
    /** The period's first supported comparison, the one every screen shows. */
    fun firstSupported(rows: List<ForecastComparisonRow>, start: Long): ForecastComparisonRow? =
        firstPerPeriod(rows.filter { it.periodStart == start && supports(it) }).firstOrNull()
    fun hasComparison(rows: List<ForecastComparisonRow>, start: Long) = rows.any { it.periodStart == start && supports(it) }
    fun evaluate(rows: List<ForecastComparisonRow>): ForecastPerformance {
        val usable = firstPerPeriod(rows.filter { it.actual != null && supports(it) })
        var pausedAfter: Long? = null
        for(index in 2 until usable.size) {
            val window = usable.subList(index-2,index+1)
            if(pausedAfter != null && window.first().periodStart <= pausedAfter) continue
            val personal = error(window, true)
            val baseline = error(window, false)
            if(pausedAfter == null && personal > baseline.multiply(BigDecimal("1.1"))) pausedAfter = window.last().periodStart
            else if(pausedAfter != null && baseline.signum() > 0 && personal <= baseline.multiply(BigDecimal("0.9"))) pausedAfter = null
        }
        val latest = usable.takeLast(3)
        return ForecastPerformance(pausedAfter == null, usable.size,
            latest.takeIf { it.isNotEmpty() }?.let { averageError(it,true) },
            latest.takeIf { it.isNotEmpty() }?.let { averageError(it,false) },pausedAfter,
            firstPerPeriod(rows.filter { supports(it) }).takeLast(3).map { row ->
                ForecastComparisonView(row.periodStart, row.periodEnd, row.issuedAt, originDay(row), row.personalTotal, row.baselineTotal, row.actual)
            })
    }
    private fun firstPerPeriod(rows: List<ForecastComparisonRow>) =
        rows.groupBy { it.periodStart }.values.map { group -> group.minBy { it.issuedAt } }.sortedBy { it.periodStart }
    private fun averageError(rows: List<ForecastComparisonRow>, personal: Boolean) = error(rows,personal)
        .divide(BigDecimal(rows.size),0,java.math.RoundingMode.HALF_EVEN).min(BigDecimal.valueOf(Long.MAX_VALUE)).longValueExact()
    private fun error(rows: List<ForecastComparisonRow>, personal: Boolean) = rows.fold(BigDecimal.ZERO) { total,row ->
        total + BigDecimal.valueOf(row.actual!!).subtract(BigDecimal.valueOf(if(personal) row.personalTotal else row.baselineTotal)).abs()
    }
}
