package com.baraa.masroof.application.analytics

import com.baraa.masroof.analytics.CleanData
import java.time.Instant
import java.time.ZoneId

/** Presentation facts only. Never creates a historical forecast or changes financial totals. */
enum class ForecastComparisonStatus { UNAVAILABLE, AWAITING_ELIGIBLE_FORECAST, WAITING_FOR_ANCHOR, NO_QUALIFIED_FORECAST, WAITING_FOR_PERIOD, INCOMPLETE_COVERAGE, READY }
data class ForecastComparisonExplanation(val status: ForecastComparisonStatus, val anchorDay: Long? = null, val enabledDay: Long? = null)
data class AnalysisDailyBar(val day: Long, val amount: Long?, val expected: Boolean)
enum class ForecastMethod { UNAVAILABLE, INITIAL, PERSONAL }
data class ForecastExplanation(val method: ForecastMethod, val evaluations: List<AnalysisEvaluation>) {
    /** Every qualified horizon came out below actual spending (positive bias = estimate lower than actual). */
    val underestimates: Boolean get() = evaluations.isNotEmpty() && evaluations.all { it.bias > 0 }
}
/** [daysUntil] is null for an undated due; [share] is its part of the confirmed total, 0 for estimates. */
data class AnalysisDueRow(val due: AnalysisDue, val daysUntil: Long?, val share: Float)
data class AnalysisCommitmentsView(val confirmedTotal: Long, val confirmedCount: Int, val rows: List<AnalysisDueRow>,
    val daysToPeriodEnd: Long) {
    /** Dated dues before the period ends, counted per day in row order. */
    val dueCountsByDay: Map<Long, Int> get() = rows.mapNotNull { it.daysUntil }.filter { it in 0..daysToPeriodEnd }
        .groupingBy { it }.eachCount()
}
data class AnalysisCompositionPart(val group: com.baraa.masroof.application.dashboard.AnalysisSpendingGroup, val percent: Int, val share: Float)

/** Progress toward one personal-forecast history target. */
data class AnalysisReadinessStep(val count: Int, val target: Int) {
    val fraction: Float get() = if (target <= 0) 0f else (count.toFloat() / target).coerceIn(0f, 1f)
}
/** [days] is null while consecutive complete days are unknown. */
data class AnalysisReadiness(val days: AnalysisReadinessStep?, val periods: AnalysisReadinessStep)

object AnalysisPresentationBuilder {
    fun readiness(snapshot: AnalysisSnapshot) = AnalysisReadiness(
        snapshot.quality.consecutiveCompleteDays?.let { AnalysisReadinessStep(it, com.baraa.masroof.analytics.PurchaseForecaster.MIN_PERSONAL_DAYS) },
        AnalysisReadinessStep(snapshot.completedPeriods, com.baraa.masroof.analytics.PurchaseForecaster.MIN_PERSONAL_PERIODS))

    fun forecast(snapshot: AnalysisSnapshot): ForecastExplanation {
        val method = when {
            snapshot.quality.blocked || snapshot.status != "READY" || snapshot.total == null -> ForecastMethod.UNAVAILABLE
            snapshot.method == "WEEKDAY_MEDIAN" -> ForecastMethod.INITIAL
            snapshot.method == com.baraa.masroof.analytics.AnalyticsVersion.MODEL -> ForecastMethod.PERSONAL
            else -> ForecastMethod.UNAVAILABLE
        }
        val evaluations = if (method == ForecastMethod.UNAVAILABLE ||
            snapshot.evaluationVersion != com.baraa.masroof.analytics.AnalyticsVersion.EVALUATION) emptyList() else
            snapshot.evaluations.filter { it.horizon in setOf(1, 7, 14, 28) && it.count >= 8 && it.mae >= 0 }
                .distinctBy { it.horizon }.sortedBy { it.horizon }
        return ForecastExplanation(method, evaluations)
    }

    fun comparison(state: AnalysisUiState, today: Long, zone: ZoneId): ForecastComparisonExplanation {
        val snapshot = state.snapshot ?: return ForecastComparisonExplanation(ForecastComparisonStatus.UNAVAILABLE)
        val anchor = snapshot.periodStart + 7
        val enabled = state.preferences.enabledAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
        val row = state.forecastComparisons.filter { it.periodStart == snapshot.periodStart && ForecastPerformancePolicy.supports(it) }.minByOrNull { it.issuedAt }
        val status = when {
            row?.actual != null -> ForecastComparisonStatus.READY
            row != null && today < row.periodEnd -> ForecastComparisonStatus.WAITING_FOR_PERIOD
            row != null -> ForecastComparisonStatus.INCOMPLETE_COVERAGE
            today < anchor -> ForecastComparisonStatus.WAITING_FOR_ANCHOR
            today < snapshot.periodEnd -> ForecastComparisonStatus.AWAITING_ELIGIBLE_FORECAST
            else -> ForecastComparisonStatus.NO_QUALIFIED_FORECAST
        }
        return ForecastComparisonExplanation(status, anchor, enabled)
    }

    fun dailyBars(snapshot: AnalysisSnapshot): List<AnalysisDailyBar> = snapshot.days.groupBy { it.day }.toSortedMap().map { (day, rows) ->
        val actual = rows.mapNotNull { it.actual }.takeIf { it.isNotEmpty() }?.fold(0L, Math::addExact)
        val forecast = rows.mapNotNull { it.forecast }.maxOrNull()
        val expected = forecast != null && (actual == null || forecast > actual)
        val gap = snapshot.quality.coverageDays.firstOrNull { it.day == day }?.covered == false
        AnalysisDailyBar(day, if (gap && !expected) null else if (expected) forecast else actual ?: 0, expected)
    }

    /** Dues keep the snapshot's nearest-first order; only confirmed dues make up the total. */
    fun commitments(snapshot: AnalysisSnapshot, today: Long): AnalysisCommitmentsView {
        val confirmed = snapshot.dues.filter { it.confirmed }
        val total = confirmed.fold(0L) { sum, due -> Math.addExact(sum, due.amount) }
        return AnalysisCommitmentsView(total, confirmed.size, snapshot.dues.map { due ->
            AnalysisDueRow(due, due.day?.let { it - today },
                if (due.confirmed && total > 0 && due.amount > 0) (due.amount.toDouble() / total).toFloat() else 0f)
        }, (snapshot.periodEnd - 1 - today).coerceAtLeast(0))
    }

    /** Positive spending components as shares of their sum; refunds stay a separate, subtracted line. */
    fun composition(snapshot: AnalysisSnapshot): List<AnalysisCompositionPart> {
        val parts = snapshot.insights?.components.orEmpty().filter { it.amount > 0 }
        val total = parts.fold(0L) { sum, part -> Math.addExact(sum, part.amount) }
        if (total <= 0) return emptyList()
        return parts.map { part ->
            AnalysisCompositionPart(part, java.math.BigDecimal.valueOf(part.amount).multiply(java.math.BigDecimal(100))
                .divide(java.math.BigDecimal.valueOf(total), 0, java.math.RoundingMode.HALF_EVEN).toInt(),
                (part.amount.toDouble() / total).toFloat())
        }
    }

    fun coverage(data: CleanData, today: Long): List<AnalysisCoverageDay> {
        val from = data.days.firstOrNull { it.covered }?.day ?: return emptyList()
        return data.days.filter { it.day in from..today }.map { AnalysisCoverageDay(it.day, it.covered) }
    }

    fun consecutiveCompleteDays(data: CleanData, today: Long): Int =
        data.days.filter { it.day < today }.takeLastWhile { it.covered }.size
}
