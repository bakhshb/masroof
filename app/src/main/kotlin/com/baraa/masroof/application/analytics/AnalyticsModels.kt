package com.baraa.masroof.application.analytics

import com.baraa.masroof.analytics.*
import com.baraa.masroof.data.analytics.AnalysisAlertRow
import com.baraa.masroof.data.analytics.AnalysisPreferencesRow
import kotlinx.serialization.Serializable

@Serializable data class AnalysisDay(val day: Long, val actual: Long?, val forecast: Long?)
/** [source] and [loanTypeFallback] let screens name a commitment due the way the commitments list does. */
@Serializable data class AnalysisDue(val id: String, val name: String, val amount: Long, val day: Long?, val confirmed: Boolean,
    val source: com.baraa.masroof.application.dashboard.CommitmentDashboardSource? = null,
    val loanTypeFallback: com.baraa.masroof.domain.model.LoanType? = null)

/** Nearest due first, undated last; stable, so a confirmed due stays ahead of an estimate on the same day. */
internal fun List<AnalysisDue>.nearestFirst() = sortedWith(compareBy<AnalysisDue> { it.day == null }.thenBy { it.day })
@Serializable data class AnalysisCandidate(val key: String, val name: String, val amount: Long, val lower: Long,
    val upper: Long, val nextDay: Long, val cadence: String, val sourceIds: List<String>, val latestDay: Long,
    val latestAmount: Long, val referenceAmount: Long?, val increased: Boolean, val overdue: Boolean = false,
    val seriesKey: String? = null, val origin: String = "RULES", val modelVersion: String? = null)
@Serializable data class AnalysisCoverageDay(val day: Long, val covered: Boolean)
@Serializable data class AnalysisQuality(val used: Int, val excluded: Map<String, List<String>>, val unusual: List<String>,
    val coveredDays: Int, val coverageKnown: Boolean, val pendingReviews: Int, val blocked: Boolean,
    val historyFrom: Long? = null, val historyThrough: Long? = null,
    val reasons: List<String> = emptyList(),
    val coverageDays: List<AnalysisCoverageDay> = emptyList(), val consecutiveCompleteDays: Int? = null)
@Serializable data class AnalysisEvaluation(val horizon: Int, val count: Int, val mae: Long, val bias: Long)
@Serializable data class AnalysisSnapshot(val periodStart: Long, val periodEnd: Long, val issuedAt: Long,
    val sourceHash: String, val status: String, val method: String, val actual: Long, val remaining: Long?,
    val total: Long?, val lower: Long?, val upper: Long?, val days: List<AnalysisDay>, val quality: AnalysisQuality,
    val candidates: List<AnalysisCandidate>, val dues: List<AnalysisDue>, val completedPeriods: Int,
    val cleaningVersion: String = AnalyticsVersion.CLEANING, val featureVersion: String = AnalyticsVersion.FEATURES,
    val engineVersion: String = AnalyticsVersion.MODEL,
    val trainingFrom: Long? = null, val trainingThrough: Long? = null,
    val insights: com.baraa.masroof.application.dashboard.AnalysisSpendingInsights? = null,
    val evaluations: List<AnalysisEvaluation> = emptyList(), val modelReason: String? = null,
    val evaluationVersion: String? = null, val wholeDaysFrom: Long? = null,
    val wholeDaysLower: Long? = null, val wholeDaysUpper: Long? = null,
    val budgetLower: Long? = null, val recurringLearning: RecurringLearningStatus? = null)

@Serializable data class RecurringLearningStatus(val method: String, val reason: String,
    val trainingSeries: Int, val validationSeries: Int)
data class AnalysisRecurringCorrection(val seriesKey: String, val name: String, val kind: String)
data class AnalysisUiState(val snapshot: AnalysisSnapshot? = null, val updating: Boolean = true,
    val unavailable: Boolean = false, val alerts: List<AnalysisAlertRow> = emptyList(),
    val preferences: AnalysisPreferencesRow = AnalysisPreferencesRow(), val history: List<AnalysisSnapshot> = emptyList(),
    val historyActuals: Map<Long, Long> = emptyMap(),
    val purchasePeriods: List<AnalysisPeriod> = emptyList(),
    val forecastPerformance: ForecastPerformance = ForecastPerformance(),
    val forecastComparisons: List<com.baraa.masroof.data.analytics.ForecastComparisonRow> = emptyList(),
    val learning: com.baraa.masroof.application.interpretation.LearningInsights? = null,
    val recurringCorrections: List<AnalysisRecurringCorrection> = emptyList(),
    val observations: List<SpendingObservation> = emptyList())

data class AnalysisChartPoint(val day: Long, val amount: java.math.BigDecimal, val expected: Boolean)
fun analysisAmount(cents: Long) = com.baraa.masroof.application.dashboard.SignedMoneyAmount(
    java.math.BigDecimal.valueOf(cents, 2), com.baraa.masroof.core.money.Currency.SAR)
fun analysisAmount(amount: java.math.BigDecimal) = com.baraa.masroof.application.dashboard.SignedMoneyAmount(
    amount, com.baraa.masroof.core.money.Currency.SAR)
fun AnalysisSnapshot.chart(): List<AnalysisChartPoint> = days.groupBy { it.day }.toSortedMap().map { (day, rows) ->
    val actual = rows.mapNotNull { it.actual }.sum()
    val expected = rows.mapNotNull { it.forecast }.firstOrNull()
    AnalysisChartPoint(day, java.math.BigDecimal.valueOf(if (expected == null) actual else maxOf(actual, expected), 2), expected != null)
}

@Serializable data class StoredHuber(val weights: List<Double>, val means: List<Double>, val scales: List<Double>,
    val through: Long, val fingerprint: String, val baselineMae: Double, val modelMae: Double,
    val errors: Map<Int, List<Double>>, val accepted: Boolean, val features: String,
    val modelVersion: String = AnalyticsVersion.MODEL, val cleaningVersion: String = AnalyticsVersion.CLEANING, val from: Long = 0,
    val signedErrors: Map<Int, List<Double>> = emptyMap(), val wholeDayErrors: Map<Int, List<Double>> = emptyMap(), val evaluationVersion: String? = null) {
    fun model() = HuberModel(weights, means, scales, through, fingerprint, baselineMae, modelMae, errors, accepted, features, from, signedErrors, wholeDayErrors)
    companion object { fun from(m: HuberModel) = StoredHuber(m.weights, m.means, m.scales, m.trainedThrough,
        m.trainingFingerprint, m.baselineMae, m.modelMae, m.errorsByHorizon, m.accepted, m.featureVersion, from = m.trainedFrom,
        signedErrors = m.signedErrors, wholeDayErrors = m.wholeDayErrors, evaluationVersion = AnalyticsVersion.EVALUATION) }
}
@Serializable data class StoredBaseline(val medians: List<Long>, val from: Long, val through: Long,
    val errors: Map<Int, List<Double>>, val kind: String = "WEEKDAY_MEDIAN",
    val cleaningVersion: String = AnalyticsVersion.CLEANING, val featureVersion: String = AnalyticsVersion.FEATURES,
    val engineVersion: String = AnalyticsVersion.MODEL)

/** Read models only; neither historical totals nor evidence are copied into the sidecar. */
data class AnalysisPeriod(val start: Long, val end: Long, val actual: Long?, val coveredDays: Int, val totalDays: Int) {
    val complete: Boolean get() = coveredDays == totalDays
}
data class AnalysisEvidence(val id: String,
    val transaction: com.baraa.masroof.domain.model.FinancialTransaction?,
    val facts: com.baraa.masroof.application.dashboard.DashboardTransactionFacts?,
    val cards: List<com.baraa.masroof.domain.model.CardRegistryEntry> = emptyList())

internal fun purchasePeriodHistory(data: CleanData, currentStart: Long): List<AnalysisPeriod> {
    var period = com.baraa.masroof.domain.period.FinancialPeriodPolicy.periodContaining(java.time.LocalDate.ofEpochDay(currentStart))
    return (1..6).map {
        period = com.baraa.masroof.domain.period.FinancialPeriodPolicy.previous(period)
        val start = period.startDate.toEpochDay(); val end = period.endDateExclusive.toEpochDay()
        val days = data.days.filter { it.day in start until end }
        com.baraa.masroof.application.dashboard.AnalysisPeriodHistoryCalculator.period(start, end, days)
    }.reversed()
}

fun visibleAnalysisAlerts(state: AnalysisUiState, now: Long): List<com.baraa.masroof.data.analytics.AnalysisAlertRow> =
    state.alerts.filter { !it.resolved && it.snoozedUntil <= now }.sortedByDescending { it.observedAt }

/** The id of this due's DUE alert, `due:<id>:<day>`; the one place the format is built. */
val AnalysisDue.alertId: String get() = "due:$id:$day"

/** A card due's alert, recognisable from its id after the due leaves the snapshot. */
fun com.baraa.masroof.data.analytics.AnalysisAlertRow.isCardDue(): Boolean =
    kind == "DUE" && id.startsWith("due:" + com.baraa.masroof.application.dashboard.CREDIT_COMMITMENT_KEY_PREFIX)

/** Due epoch day of a DUE alert, the last part of [alertId]. */
fun com.baraa.masroof.data.analytics.AnalysisAlertRow.dueEpochDay(): Long? =
    if (kind == "DUE") id.substringAfterLast(':').toLongOrNull() else null

/** The snapshot due a DUE alert was raised for, matching the `due:<id>:<day>` key from [AnalysisAlertPolicy]. */
fun AnalysisSnapshot.dueFor(alert: com.baraa.masroof.data.analytics.AnalysisAlertRow): AnalysisDue? =
    if (alert.kind == "DUE") dues.firstOrNull { it.alertId == alert.id } else null

fun AnalysisUiState.dueFor(alert: com.baraa.masroof.data.analytics.AnalysisAlertRow): AnalysisDue? = snapshot?.dueFor(alert)

fun normalizeAnalysisNumber(value: String): String = value.trim().map { c ->
    when { c == '٫' -> '.'; c.isDigit() -> Character.digit(c, 10).toString().single(); else -> c }
}.joinToString("")

private val THOUSANDS_GROUPED = Regex("[0-9]{1,3}([,٬][0-9]{3})+(\\.[0-9]+)?")

/**
 * Accepts Arabic digits and decimal mark, and thousands separators only in a valid grouping:
 * a comma typed as a decimal mark (`12,50`) stays unparsed instead of becoming 1250.
 */
fun parseAnalysisAmount(text: String): java.math.BigDecimal? {
    val normalized = normalizeAnalysisNumber(text)
    val plain = if (THOUSANDS_GROUPED.matches(normalized)) normalized.replace(",", "").replace("٬", "") else normalized
    return plain.toBigDecimalOrNull()
}

data class AnalysisHistoryView(val periods: List<AnalysisPeriod>, val available: Int, val total: Long?, val chartPeriods: List<AnalysisPeriod>,
    val completeCount: Int, val average: Long?, val latestChange: Long?)
fun AnalysisUiState.purchaseHistory(count: Int): AnalysisHistoryView {
    require(count == 3 || count == 6)
    return com.baraa.masroof.application.dashboard.AnalysisPeriodHistoryCalculator.summarize(purchasePeriods.takeLast(count))
}

data class AnalysisHistoryGroup(val snapshots: List<AnalysisSnapshot>)
fun AnalysisUiState.forecastHistoryGroups(): List<AnalysisHistoryGroup> {
    val groups = mutableListOf<MutableList<AnalysisSnapshot>>()
    history.forEach { value ->
        val prior = groups.lastOrNull()?.lastOrNull()
        if (prior != null && prior.periodStart == value.periodStart && prior.periodEnd == value.periodEnd &&
            prior.total == value.total && prior.actual == value.actual && prior.remaining == value.remaining &&
            prior.method == value.method && prior.status == value.status && prior.lower == value.lower && prior.upper == value.upper &&
            prior.wholeDaysLower == value.wholeDaysLower && prior.wholeDaysUpper == value.wholeDaysUpper) groups.last().add(value)
        else groups.add(mutableListOf(value))
    }
    return groups.map { AnalysisHistoryGroup(it.toList()) }
}

/** Count complete covered salary periods before an evaluation origin, never using later coverage. */
internal fun completedAnalysisPeriods(data: CleanData, before: Long): Int {
    val first = data.days.firstOrNull { it.covered && it.day < before }?.day ?: return 0
    var period = com.baraa.masroof.domain.period.FinancialPeriodPolicy.periodContaining(java.time.LocalDate.ofEpochDay(first))
    var count = 0
    val covered = data.days.filter { it.covered && it.day < before }.map { it.day }.toSet()
    while (period.endDateExclusive.toEpochDay() <= before) {
        if ((period.startDate.toEpochDay() until period.endDateExclusive.toEpochDay()).all { it in covered }) count++
        period = com.baraa.masroof.domain.period.FinancialPeriodPolicy.next(period)
    }
    return count
}
