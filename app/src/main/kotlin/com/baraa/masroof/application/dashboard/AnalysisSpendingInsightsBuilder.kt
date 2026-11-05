package com.baraa.masroof.application.dashboard

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

data class AnalysisSpendingFact(val id: String, val day: Long, val amount: Long, val kind: String, val merchant: String?)
@Serializable data class AnalysisSpendingGroup(val key: String, val amount: Long, val ids: List<String>, val name: String? = null)
@Serializable data class AnalysisSpendingChange(val group: AnalysisSpendingGroup, val previous: Long, val delta: Long,
    val previousIds: List<String>)
@Serializable data class AnalysisSpendingComparison(val from: Long, val through: Long, val previousFrom: Long,
    val previousThrough: Long, val current: Long, val previous: Long, val delta: Long, val percent: String?,
    val changes: List<AnalysisSpendingChange>)
@Serializable data class AnalysisSpendingInsights(val components: List<AnalysisSpendingGroup>,
    val merchants: List<AnalysisSpendingGroup>, val comparison: AnalysisSpendingComparison?)

/** Same detached, scoped contributions as the forecast. No repository reads or SMS interpretation. */
object AnalysisSpendingInsightsBuilder {
    private val kinds = listOf("PURCHASES", "CASH", "TRANSFERS", "REFUNDS")
    private fun total(rows: List<AnalysisSpendingFact>) = rows.fold(0L) { sum, row -> Math.addExact(sum, row.amount) }
    private fun components(rows: List<AnalysisSpendingFact>) = kinds.map { kind ->
        val members = rows.filter { it.kind == kind }
        AnalysisSpendingGroup(kind, total(members), members.sortedWith(compareByDescending<AnalysisSpendingFact> { it.day }.thenBy { it.id }).map { it.id })
    }
    private fun merchants(rows: List<AnalysisSpendingFact>) = rows.filter { it.kind in setOf("PURCHASES", "REFUNDS") && !it.merchant.isNullOrBlank() }
        .groupBy { MerchantNameNormalizer.key(it.merchant!!) }.map { (key, members) ->
            AnalysisSpendingGroup("merchant:$key", total(members), members.sortedWith(compareByDescending<AnalysisSpendingFact> { it.day }.thenBy { it.id }).map { it.id },
                members.maxWith(compareBy<AnalysisSpendingFact> { it.day }.thenBy { it.id }).merchant)
        }

    fun build(facts: List<AnalysisSpendingFact>, start: Long, today: Long, previousStart: Long, previousEnd: Long,
        coveredDays: Set<Long>): AnalysisSpendingInsights {
        val rows = facts.distinctBy { it.id }
        val current = rows.filter { it.day in start..today }
        val elapsed = today - start // Compare complete days only; today's partial receipts cannot distort the comparison.
        val comparison = if (elapsed <= 0 || previousStart + elapsed > previousEnd ||
            (start until today).any { it !in coveredDays } ||
            (previousStart until previousStart + elapsed).any { it !in coveredDays }) null else {
            val now = rows.filter { it.day in start until today }
            val before = rows.filter { it.day in previousStart until previousStart + elapsed }
            val nowGroups = components(now) + merchants(now)
            val beforeGroups = (components(before) + merchants(before)).associateBy { it.key }
            val groups = (nowGroups.map { it.key } + beforeGroups.keys).distinct().map { key ->
                val a = nowGroups.firstOrNull { it.key == key } ?: beforeGroups.getValue(key).copy(amount = 0, ids = emptyList())
                val b = beforeGroups[key]
                AnalysisSpendingChange(a, b?.amount ?: 0, Math.subtractExact(a.amount, b?.amount ?: 0), b?.ids.orEmpty())
            }.filter { it.delta != 0L }.sortedWith(compareByDescending<AnalysisSpendingChange> { BigDecimal.valueOf(it.delta).abs() }.thenBy { it.group.key }).take(3)
            val a = total(now); val b = total(before); val delta = Math.subtractExact(a, b)
            AnalysisSpendingComparison(start, today - 1, previousStart, previousStart + elapsed - 1, a, b, delta,
                if (b <= 0) null else BigDecimal.valueOf(delta).multiply(BigDecimal(100)).divide(BigDecimal.valueOf(b), 1, RoundingMode.HALF_EVEN).toPlainString(), groups)
        }
        return AnalysisSpendingInsights(components(current), merchants(current).filter { it.amount > 0 }.sortedWith(
            compareByDescending<AnalysisSpendingGroup> { it.amount }.thenBy { it.key }), comparison)
    }
}

/** Historical money aggregates stay in the application calculation layer. */
object AnalysisPeriodHistoryCalculator {
    fun period(start: Long, end: Long, days: List<com.baraa.masroof.analytics.PurchaseDay>): com.baraa.masroof.application.analytics.AnalysisPeriod {
        val covered = days.count { it.covered }
        return com.baraa.masroof.application.analytics.AnalysisPeriod(start, end,
            if (covered == 0) null else days.fold(0L) { sum, day -> Math.addExact(sum, day.net) }, covered, (end - start).toInt())
    }

    fun summarize(periods: List<com.baraa.masroof.application.analytics.AnalysisPeriod>): com.baraa.masroof.application.analytics.AnalysisHistoryView {
        val amounts = periods.mapNotNull { it.actual }
        val complete = periods.filter { it.complete && it.actual != null }
        val connected = periods.takeLastWhile { it.complete && it.actual != null }
        return com.baraa.masroof.application.analytics.AnalysisHistoryView(periods, amounts.size,
            amounts.takeIf { it.isNotEmpty() }?.fold(0L, Math::addExact), connected, complete.size,
            complete.takeIf { it.isNotEmpty() }?.let {
                BigDecimal(it.fold(0L) { sum, p -> Math.addExact(sum, p.actual!!) }).divide(BigDecimal(it.size), 0, RoundingMode.HALF_EVEN).longValueExact()
            }, connected.takeIf { it.size >= 2 }?.let { Math.subtractExact(it.last().actual!!, it[it.lastIndex - 1].actual!!) })
    }
}
