package com.baraa.masroof.analytics

import kotlin.math.exp
import kotlin.math.sqrt

/** The model scores the first [RecurringLearning.CLASSES] labels; YEARLY comes only from rules or a human decision. */
enum class RecurringLabel { NONE, WEEKLY, MONTHLY, YEARLY }
data class RecurringPrediction(val label: RecurringLabel, val score: Double,
    val origin: String = "MODEL", val modelVersion: String? = null)
data class RecurringExample(val seriesKey: String, val features: List<Double>, val label: RecurringLabel, val decidedAt: Long) {
    init { require(seriesKey.isNotBlank() && decidedAt >= 0); RecurringLearning.requireFeatures(features) }
}
data class RecurringModel(val version: String, val weights: List<List<Double>>, val accepted: Boolean = false,
    val featureVersion: String = RecurringLearning.FEATURE_VERSION, val threshold: Double = .8) {
    init {
        require(version.isNotBlank() && featureVersion == RecurringLearning.FEATURE_VERSION)
        require(weights.size == 3 && weights.all { row -> row.size == 9 && row.all { it.isFinite() && kotlin.math.abs(it) <= 100 } })
        require(threshold.isFinite() && threshold in .8.. .99)
    }
    fun predict(features: List<Double>): RecurringPrediction? {
        RecurringLearning.requireFeatures(features)
        val scores = probabilities(features)
        val index = scores.indices.maxBy { scores[it] }
        return RecurringPrediction(RecurringLabel.entries[index], scores[index], modelVersion = version).takeIf { it.score >= threshold }
    }
    internal fun probabilities(features: List<Double>): List<Double> {
        val logits = weights.map { row -> row[0] + features.indices.sumOf { row[it + 1] * features[it] } }
        val maximum = logits.max()
        val values = logits.map { exp(it - maximum) }
        return values.map { it / values.sum() }
    }
}
data class RecurringPersonalization(val model: RecurringModel?, val personalUsed: Boolean,
    val trainingSeries: Int, val validationSeries: Int, val reason: String)

/** Small regularized softmax classifier. Only explicit human labels are training truth. */
object RecurringLearning {
    const val FEATURE_VERSION = "recurring-numeric-v1"
    const val CLASSES = 3
    fun requireFeatures(features: List<Double>) {
        require(features.size == 8 && features.all { it.isFinite() && it in 0.0..4.0 })
    }
    fun isValidation(seriesKey: String) = hash("recurring-personal-v1|$seriesKey").take(8).toLong(16) % 5 == 0L

    fun fit(examples: List<RecurringExample>, prior: RecurringModel? = null): RecurringModel {
        val rows = latest(examples)
        require(rows.isNotEmpty())
        val initial = prior?.weights ?: List(3) { List(9) { 0.0 } }
        val weights = initial.map { it.toDoubleArray() }.toTypedArray()
        repeat(600) { iteration ->
            val gradient = Array(3) { DoubleArray(9) }
            for (row in rows) {
                val x = listOf(1.0) + row.features
                val logits = weights.map { w -> x.indices.sumOf { w[it] * x[it] } }
                val maximum = logits.max()
                val probabilities = logits.map { exp(it - maximum) }.let { values -> values.map { it / values.sum() } }
                for (c in 0..2) for (j in 0..8) {
                    gradient[c][j] += (probabilities[c] - if (row.label.ordinal == c) 1.0 else 0.0) * x[j] / rows.size
                }
            }
            val rate = .5 / sqrt(1 + iteration / 100.0)
            for (c in 0..2) for (j in 0..8) weights[c][j] -= rate *
                (gradient[c][j] + .005 * (weights[c][j] - initial[c][j]))
        }
        val fingerprint = hash(rows.joinToString { "${it.seriesKey}:${it.label}:${it.features}:${it.decidedAt}" })
        return RecurringModel("recurring-logistic-v1-${fingerprint.take(16)}", weights.map { it.toList() })
    }

    fun personalize(seed: RecurringModel?, examples: List<RecurringExample>, safety: List<RecurringExample>): RecurringPersonalization {
        val rows = latest(examples)
        val training = rows.filterNot { isValidation(it.seriesKey) }
        val heldOut = rows.filter { isValidation(it.seriesKey) }
        // 600 gradient passes are wasted when no eligible seed exists to compare against.
        val candidate = training.takeIf { it.isNotEmpty() && seed?.accepted == true }?.let { fit(it, seed) }
        val baseline = seed?.takeIf { it.accepted }
        fun correct(model: RecurringModel?, rows: List<RecurringExample>) = rows.count { model?.predict(it.features)?.label == it.label }
        fun wrong(model: RecurringModel?, rows: List<RecurringExample>) = rows.count { row ->
            model?.predict(row.features)?.label?.let { it != RecurringLabel.NONE && it != row.label } == true }
        val reason = when {
            baseline == null -> "SEED_NOT_ELIGIBLE"
            heldOut.size < 8 || heldOut.count { it.label == RecurringLabel.NONE } < 2 || heldOut.count { it.label != RecurringLabel.NONE } < 2 -> "NEEDS_EVALUATIONS"
            candidate == null -> "NEEDS_TRAINING_LABELS"
            safety.isEmpty() || wrong(candidate, safety) > wrong(baseline, safety) -> "SAFETY_VALIDATION_FAILED"
            wrong(candidate, heldOut) > wrong(baseline, heldOut) -> "BASELINE_BETTER"
            correct(candidate, heldOut) <= correct(baseline, heldOut) -> "BASELINE_BETTER"
            !precise(candidate, heldOut) || !precise(candidate, safety) -> "BASELINE_BETTER"
            else -> "PERSONAL_MODEL"
        }
        val accepted = reason == "PERSONAL_MODEL"
        return RecurringPersonalization(if (accepted) candidate?.copy(accepted = true) else baseline,
            accepted, training.size, heldOut.size, reason)
    }

    private fun precise(model: RecurringModel, rows: List<RecurringExample>): Boolean {
        val proposed = rows.mapNotNull { row -> model.predict(row.features)?.takeIf { it.label != RecurringLabel.NONE }?.let { row to it } }
        return proposed.isNotEmpty() && proposed.count { (row, p) -> row.label == p.label }.toDouble() / proposed.size >= .95
    }
    private fun latest(examples: List<RecurringExample>) = examples.groupBy { it.seriesKey }.map { (_, rows) ->
        // Conflicting simultaneous decisions are not resolved by list order.
        val latest = rows.filter { it.decidedAt == rows.maxOf { it.decidedAt } }
        latest.distinctBy { it.label to it.features }.singleOrNull()
    }.filterNotNull().filter { it.label.ordinal < CLASSES }.sortedBy { it.seriesKey }.also { require(it.size <= 5000) }
}
