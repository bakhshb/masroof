package com.baraa.masroof.analytics

import java.time.DateTimeException
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.*

data class HuberModel(val weights: List<Double>, val means: List<Double>, val scales: List<Double>,
    val trainedThrough: Long, val trainingFingerprint: String, val baselineMae: Double,
    val modelMae: Double, val errorsByHorizon: Map<Int, List<Double>>, val accepted: Boolean,
    val featureVersion: String = AnalyticsVersion.FEATURES, val trainedFrom: Long = 0,
    val signedErrors: Map<Int, List<Double>> = emptyMap(), val wholeDayErrors: Map<Int, List<Double>> = emptyMap())
data class WeekdayModel(val medians: List<Long>, val from: Long, val through: Long, val errors: Map<Int, List<Double>>,
    val signedErrors: Map<Int, List<Double>> = emptyMap(), val wholeDayErrors: Map<Int, List<Double>> = emptyMap())
data class HorizonEvaluation(val horizon: Int, val count: Int, val mae: Long, val bias: Long)
data class ForecastPoint(val day: Long, val gross: Long)
data class PurchaseForecast(val status: String, val method: String, val actual: Long,
    val remaining: Long?, val total: Long?, val lower: Long?, val upper: Long?,
    val points: List<ForecastPoint>, val model: HuberModel?, val baseline: WeekdayModel? = null,
    val wholeDaysLower: Long? = null, val wholeDaysUpper: Long? = null,
    val evaluations: List<HorizonEvaluation> = emptyList(), val reason: String = "INSUFFICIENT_HISTORY",
    val personalTotal: Long? = null, val baselineTotal: Long? = null)

/** Numeric-only engine. All transforms and evaluation fits use the training prefix. */
object PurchaseForecaster {
    fun forecast(data: CleanData, periodStart: Long, periodEnd: Long, today: Long, completedPeriods: Int,
        phase: (Long) -> Int, previous: HuberModel? = null,
        completedPeriodsAt: (Long) -> Int = { completedPeriods }, allowPersonal: Boolean = true): PurchaseForecast {
        require(periodEnd > periodStart && today in periodStart until periodEnd)
        val actual = data.days.filter { it.day >= periodStart }.fold(0L) { sum, d -> Math.addExact(sum, d.net) }
        val history = data.days.filter { it.day < today && it.covered }
        if (!data.quality.coverageKnown || history.size < 56 || completedPeriods < 2) return PurchaseForecast(
            "INSUFFICIENT_DATA", "NONE", actual, null, null, null, null, emptyList(), null)
        val lastGap = history.zipWithNext().indexOfLast { (a, b) -> b.day != a.day + 1 }
        // The newest year is enough to learn habits and keeps each refit's cost bounded.
        val training = history.drop(if (lastGap < 0) 0 else lastGap + 1).takeIf { it.lastOrNull()?.day == today - 1 }.orEmpty()
            .takeLast(MAX_TRAINING_DAYS)
        var model: HuberModel? = null
        if (training.size >= MIN_PERSONAL_DAYS && completedPeriods >= MIN_PERSONAL_PERIODS && training.count { it.gross > 0 } >= 20) {
            val canReuse = previous != null && previous.featureVersion == AnalyticsVersion.FEATURES &&
                today - previous.trainedThrough <= 7 && previous.trainingFingerprint == fingerprint(training.filter { it.day in previous.trainedFrom..previous.trainedThrough })
            model = if (canReuse) previous else runCatching { evaluateAndFit(training, phase, completedPeriodsAt) }.getOrNull()
        }
        val chosen = model?.takeIf { it.accepted && allowPersonal }
        val predictions = predict(if (chosen == null) history else training, today, periodEnd - 1, phase, chosen)
        val todayGross = data.days.firstOrNull { it.day == today }?.gross ?: 0
        fun remainingFor(points: List<ForecastPoint>) = points.fold(0L) { sum, point -> Math.addExact(sum,
            if (point.day == today) (point.gross - todayGross).coerceAtLeast(0) else point.gross) }
        val remaining = remainingFor(predictions)
        val total = Math.addExact(actual, remaining)
        fun projectedTotal(points: List<ForecastPoint>) = Math.addExact(actual, remainingFor(points))
        val baselineTotal = projectedTotal(predict(history,today,periodEnd - 1,phase,null))
        val personalTotal = model?.takeIf { it.accepted }?.let { projectedTotal(predict(training,today,periodEnd - 1,phase,it)) }
        val horizon = (periodEnd - today - 1).toInt()
        val baselineEvaluation = evaluate(history, phase, false, completedPeriodsAt)
        val baseSigned = baselineEvaluation.signed
        val baseErrors = baseSigned.mapValues { (_, errors) -> errors.map(::abs) }
        val signed = chosen?.signedErrors ?: baseSigned
        val errors = (chosen?.wholeDayErrors ?: baselineEvaluation.wholeDays)[horizon].orEmpty().map(::abs)
        val margin = errors.sorted().takeIf { it.size >= 8 }?.let { it[(ceil(it.size * .9).toInt() - 1).coerceIn(it.indices)].roundToLong() }
        val wholeDays = predictions.filter { it.day > today }.fold(0L) { sum, p -> Math.addExact(sum, p.gross) }
        val reason = when {
            chosen != null -> "PERSONAL_MODEL"
            !allowPersonal && model?.accepted == true -> "LIVE_BASELINE_BETTER"
            training.size < MIN_PERSONAL_DAYS -> if (history.size >= MIN_PERSONAL_DAYS) "COVERAGE_GAPS" else "NEEDS_HISTORY"
            completedPeriods < MIN_PERSONAL_PERIODS -> "NEEDS_PERIODS"
            training.count { it.gross > 0 } < 20 -> "NEEDS_PURCHASE_DAYS"
            model == null -> "MODEL_UNAVAILABLE"
            CORE_HORIZONS.any { model.signedErrors[it].orEmpty().size < 8 } -> "NEEDS_EVALUATIONS"
            else -> "BASELINE_BETTER"
        }
        return PurchaseForecast("READY", if (chosen == null) "WEEKDAY_MEDIAN" else AnalyticsVersion.MODEL,
            actual, remaining, total, null, null, predictions, model,
            WeekdayModel(weekdayBaseline(history).map { it.roundToLong() },
                history.groupBy { weekday(it.day) }.values.flatMap { it.takeLast(12) }.minOf { it.day }, history.last().day, baseErrors, baseSigned, baselineEvaluation.wholeDays),
            if (horizon == 0) 0 else margin?.let { (wholeDays - it).coerceAtLeast(0) },
            if (horizon == 0) 0 else margin?.let { Math.addExact(wholeDays, it) },
            CORE_HORIZONS.mapNotNull { h -> signed[h]?.takeIf { it.size >= 8 }?.let { e ->
                HorizonEvaluation(h, e.size, e.map(::abs).average().roundToLong(), e.average().roundToLong())
            } }, reason, personalTotal, baselineTotal)
    }

    private fun fingerprint(days: List<PurchaseDay>) = hash(days.joinToString { "${it.day}:${it.gross}:${it.refunds}:${it.phase}" })
    private fun features(day: Long, phase: Int, history: List<PurchaseDay>): DoubleArray {
        fun lag(n: Int) = history.firstOrNull { it.day == day - n }?.gross?.toDouble() ?: 0.0
        fun mean(n: Int) = history.filter { it.day in (day - n) until day }.map { it.gross.toDouble() }.average().takeIf { it.isFinite() } ?: 0.0
        val season = season(day)
        return doubleArrayOf(1.0, *(0..5).map { if (weekday(day) == it) 1.0 else 0.0 }.toDoubleArray(),
            phase.toDouble() / 31, lag(1) / 100, lag(7) / 100, mean(7) / 100, mean(28) / 100,
            if (phase < 3) 1.0 else 0.0, if (season == Season.RAMADAN) 1.0 else 0.0, if (season == Season.EID) 1.0 else 0.0)
    }

    internal enum class Season { NONE, RAMADAN, EID }
    /** Umm al-Qura calendar from java.time; dates outside its range carry no season. */
    internal fun season(day: Long): Season = try {
        val hijri = HijrahDate.from(LocalDate.ofEpochDay(day))
        val month = hijri.get(ChronoField.MONTH_OF_YEAR); val date = hijri.get(ChronoField.DAY_OF_MONTH)
        when {
            month == 9 -> Season.RAMADAN
            (month == 10 && date <= 3) || (month == 12 && date in 10..13) -> Season.EID
            else -> Season.NONE
        }
    } catch (_: DateTimeException) { Season.NONE }

    private fun fit(history: List<PurchaseDay>): HuberModel {
        val rows = history.drop(28).map { features(it.day, it.phase, history.filter { row -> row.day < it.day }) }
        val targets = history.drop(28).map { it.gross.toDouble() / 100 }.toDoubleArray()
        val p = rows.first().size
        val means = DoubleArray(p) { j -> if (j == 0) 0.0 else rows.map { it[j] }.average() }
        val scales = DoubleArray(p) { j -> if (j == 0) 1.0 else sqrt(rows.map { (it[j] - means[j]).pow(2) }.average()).coerceAtLeast(1e-6) }
        val x = rows.map { row -> DoubleArray(p) { j -> (row[j] - means[j]) / scales[j] } }
        var weights = DoubleArray(p); weights[0] = median(targets.toList())
        repeat(60) {
            val residuals = x.mapIndexed { i, row -> targets[i] - dot(weights, row) }
            val center = median(residuals)
            val sigma = (1.4826 * median(residuals.map { abs(it - center) })).coerceAtLeast(.01)
            val a = Array(p) { DoubleArray(p) }; val b = DoubleArray(p)
            for (i in x.indices) {
                val w = minOf(1.0, 1.35 * sigma / abs(residuals[i]).coerceAtLeast(1e-12))
                for (j in 0 until p) { b[j] += w * x[i][j] * targets[i]; for (k in 0 until p) a[j][k] += w * x[i][j] * x[i][k] }
            }
            for (j in 0 until p) a[j][j] += if (j == 0) 1e-8 else .01
            weights = solve(a, b)
        }
        check(weights.all { it.isFinite() })
        return HuberModel(weights.toList(), means.toList(), scales.toList(), history.last().day,
            fingerprint(history), 0.0, 0.0, emptyMap(), false, trainedFrom = history.first().day)
    }

    private fun predict(history: List<PurchaseDay>, from: Long, through: Long, phase: (Long) -> Int, model: HuberModel?): List<ForecastPoint> {
        val rolling = history.toMutableList()
        val baseline = weekdayBaseline(history)
        return (from..through).map { day ->
            val amount = if (model == null) baseline[weekday(day)] else {
                val x = features(day, phase(day), rolling)
                dot(model.weights.toDoubleArray(), DoubleArray(x.size) { (x[it] - model.means[it]) / model.scales[it] }) * 100
            }
            check(amount.isFinite() && amount < Long.MAX_VALUE / 4)
            val cents = amount.roundToLong().coerceAtLeast(0)
            rolling += PurchaseDay(day, cents, 0, 0, phase(day), true)
            ForecastPoint(day, cents)
        }
    }
    /**
     * A period total needs the expected (mean) day, not the typical one: medians of mostly-empty or
     * skewed days forecast far too little. One rare huge day is capped at the 98th percentile instead.
     * The method is still stored as "WEEKDAY_MEDIAN" because that persisted key predates this estimator.
     */
    internal fun weekdayBaseline(history: List<PurchaseDay>): List<Double> {
        val recent = history.groupBy { weekday(it.day) }.mapValues { (_, rows) -> rows.takeLast(12).map { it.gross.toDouble() } }
        val all = recent.values.flatten().sorted()
        val cap = all.getOrNull((all.size * .98).toInt().coerceAtMost(all.size - 1)) ?: 0.0
        return (0..6).map { dow -> recent[dow]?.map { minOf(it, cap) }?.average() ?: 0.0 }
    }

    private val CORE_HORIZONS = listOf(1, 7, 14, 28)
    private const val MAX_TRAINING_DAYS = 365
    /** History the personal model needs before it is even tried; the readiness rings show progress to these. */
    const val MIN_PERSONAL_DAYS = 120
    const val MIN_PERSONAL_PERIODS = 4

    private fun evaluateAndFit(history: List<PurchaseDay>, phase: (Long) -> Int, periods: (Long) -> Int): HuberModel {
        val candidateEvaluation = evaluate(history, phase, true, periods)
        val candidate = candidateEvaluation.signed
        // Use exactly the same eligible origins for both competitors.
        val base = evaluate(history, phase, true, periods, baseline = true).signed
        val baseMaes = CORE_HORIZONS.map { h -> base[h].orEmpty().map(::abs).average() }
        val modelMaes = CORE_HORIZONS.map { h -> candidate[h].orEmpty().map(::abs).average() }
        val sufficient = CORE_HORIZONS.all { candidate[it].orEmpty().size >= 8 }
        val ratios = baseMaes.zip(modelMaes).map { (b, m) -> if (b == 0.0) { if (m == 0.0) 1.0 else Double.POSITIVE_INFINITY } else m / b }
        val baselineMae = baseMaes.average().takeIf { it.isFinite() } ?: 0.0
        val candidateMae = modelMaes.average().takeIf { it.isFinite() } ?: 0.0
        val accepted = sufficient && ratios.all { it <= 1.1 } && ratios.average() <= .9 && candidateMae <= baselineMae * .9
        return fit(history).copy(baselineMae = baselineMae, modelMae = candidateMae,
            errorsByHorizon = candidate.mapValues { (_, errors) -> errors.map(::abs) }, signedErrors = candidate, wholeDayErrors = candidateEvaluation.wholeDays, accepted = accepted)
    }

    private data class EvaluationSamples(val signed: Map<Int, List<Double>>, val wholeDays: Map<Int, List<Double>>)

    /** Calendar horizons stay separate. Each validation window is fully covered; every fit uses only its prefix. */
    private fun evaluate(history: List<PurchaseDay>, phase: (Long) -> Int, personal: Boolean,
        periods: (Long) -> Int, baseline: Boolean = false): EvaluationSamples {
        val errors = mutableMapOf<Int, MutableList<Double>>()
        val wholeDays = mutableMapOf<Int, MutableList<Double>>()
        val minimum = if (personal) 120 else 56
        for (cut in (minimum until history.size).step(3).toList().takeLast(40)) {
            val train = history.take(cut)
            val day = history[cut].day
            if (periods(day) < if (personal) 4 else 2) continue
            if (personal && (train.count { it.gross > 0 } < 20 || train.zipWithNext().any { (a, b) -> b.day != a.day + 1 })) continue
            val validation = history.drop(cut).take(31).takeWhileIndexed { i, row -> row.day == day + i }
            if (validation.isEmpty()) continue
            val model = if (personal && !baseline) fit(train) else null
            val predicted = predict(train, day, validation.last().day, phase, model)
            var actualSum = 0.0; var predictedSum = 0.0
            validation.forEachIndexed { i, row ->
                actualSum += row.gross.toDouble(); predictedSum += predicted[i].gross.toDouble()
                errors.getOrPut(i + 1) { mutableListOf() }.add(actualSum - predictedSum)
                // Same origin as the live forecast: skip the unfinished first day, without
                // pretending its eventual full amount was known when predicting later days.
                if (i > 0) wholeDays.getOrPut(i) { mutableListOf() }.add(
                    actualSum - validation.first().gross - predictedSum + predicted.first().gross)
            }
        }
        return EvaluationSamples(errors, wholeDays)
    }

    private inline fun <T> List<T>.takeWhileIndexed(predicate: (Int, T) -> Boolean): List<T> {
        val result = ArrayList<T>()
        forEachIndexed { index, value -> if (!predicate(index, value)) return result else result.add(value) }
        return result
    }
    private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        for (i in b.indices) {
            val pivot = (i until b.size).maxBy { abs(a[it][i]) }
            val row = a[i]; a[i] = a[pivot]; a[pivot] = row
            val v = b[i]; b[i] = b[pivot]; b[pivot] = v
            check(abs(a[i][i]) > 1e-15)
            val divisor = a[i][i]; for (k in i until b.size) a[i][k] /= divisor; b[i] /= divisor
            for (j in b.indices) if (j != i) { val f = a[j][i]; for (k in i until b.size) a[j][k] -= f * a[i][k]; b[j] -= f * b[i] }
        }
        return b
    }
}
