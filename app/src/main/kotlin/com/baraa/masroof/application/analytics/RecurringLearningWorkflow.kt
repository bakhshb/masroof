package com.baraa.masroof.application.analytics

import com.baraa.masroof.data.analytics.AnalyticsJson
import com.baraa.masroof.data.analytics.RecurringFeedbackRow
import com.baraa.masroof.analytics.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

@Serializable data class StoredRecurringExample(val seriesKey: String, val features: List<Double>, val label: String, val decidedAt: Long = 0) {
    fun example() = RecurringExample(seriesKey, features, RecurringLabel.valueOf(label), decidedAt)
}
@Serializable data class StoredRecurringModel(val version: String, val weights: List<List<Double>>, val accepted: Boolean,
    val featureVersion: String = RecurringLearning.FEATURE_VERSION, val threshold: Double = .8,
    val safety: List<StoredRecurringExample> = emptyList(), val task: String = "recurring") {
    fun model(): RecurringModel {
        require(task == "recurring" && safety.size <= 5000)
        safety.forEach { it.example() }
        return RecurringModel(version, weights, accepted, featureVersion, threshold)
    }
}
@Serializable data class RecurringFeedbackPayload(val seriesKey: String, val sourceIds: List<String>,
    val label: String? = null, val featureVersion: String = RecurringLearning.FEATURE_VERSION) {
    fun validate() {
        require(seriesKey.isNotBlank() && sourceIds.size >= 2 && sourceIds.size <= 5000 && sourceIds.distinct().size == sourceIds.size)
        require(featureVersion == RecurringLearning.FEATURE_VERSION)
        label?.let { RecurringLabel.valueOf(it) }
    }
}

internal object RecurringLearningWorkflow {
    data class Result(val patterns: List<RecurringPattern>, val status: RecurringLearningStatus,
        val reviewedExamples: List<RecurringExample>, val ruleLabels: Map<String, RecurringLabel>)
    private val seed = runCatching {
        RecurringModel::class.java.getResourceAsStream("/ml/recurring-seed.json")?.bufferedReader()?.use {
            val text = it.readText(); require(text.length <= 4 * 1024 * 1024)
            AnalyticsJson.format.decodeFromString<StoredRecurringModel>(text).also { stored -> stored.model() }
        }
    }.getOrNull()
    val version get() = "${RecurringLearning.FEATURE_VERSION}|${seed?.version ?: "unavailable"}"
    @Volatile private var cachedFit: Pair<List<RecurringExample>, RecurringPersonalization>? = null

    fun build(facts: List<FinancialFact>, feedback: List<RecurringFeedbackRow>, today: Long,
        committedCadences: Map<String, String> = emptyMap()): Result {
        val series = RecurringDetector.series(facts).associateBy { it.key }
        val decisions = feedback.mapNotNull { row ->
            if (row.kind == "CONFIRM_PENDING") return@mapNotNull null
            val payload = runCatching { AnalyticsJson.format.decodeFromString<RecurringFeedbackPayload>(row.payload).also { it.validate() } }.getOrNull()
                ?: return@mapNotNull null
            val current = series[row.seriesKey] ?: return@mapNotNull null
            if (payload.seriesKey != row.seriesKey) return@mapNotNull null
            if (row.kind == "CONFIRMED" && (payload.label == null || committedCadences[payload.sourceIds.last()] != payload.label)) return@mapNotNull null
            row to (payload to current)
        }
        val explicitSeries = decisions.map { it.first.seriesKey }.toSet()
        val confirmed = series.values.filter { it.key !in explicitSeries }.mapNotNull { current ->
            val labels = current.rows.mapNotNull { committedCadences[it.id] }.filter { it in setOf("WEEKLY", "MONTHLY", "YEARLY") }.distinct()
            labels.singleOrNull()?.let { RecurringExample(current.key, current.features, RecurringLabel.valueOf(it), 0) }
        }
        val examples = confirmed + decisions.mapNotNull { (row, pair) ->
            val (payload, current) = pair
            payload.label?.takeIf { row.kind != "DISMISSED" && current.rows.map { it.id }.containsAll(payload.sourceIds) }?.let {
                RecurringExample(row.seriesKey, current.features, RecurringLabel.valueOf(it), row.decidedAt)
            }
        }
        val ordered = examples.sortedBy { it.seriesKey }
        val cached = cachedFit
        // Cache only the numerical fit; live evidence, preferences and dates are rebuilt.
        val result = if (cached?.first == ordered) cached.second else
            RecurringLearning.personalize(seed?.model(), ordered, seed?.safety.orEmpty().map { it.example() }).also { cachedFit = ordered to it }
        val model = result.model
        val overrides = decisions.associate { (row, pair) -> row.seriesKey to pair.first }
        val classify: ((RecurringSeries) -> RecurringPrediction?)? = if (model == null && overrides.isEmpty()) null else { item ->
            val decision = overrides[item.key]?.label
            if (decision != null) RecurringPrediction(RecurringLabel.valueOf(decision), 1.0, "HUMAN_DECISION")
            else if (model != null) model.predict(item.features)
            else RecurringPrediction(item.ruleLabel, 1.0, "RULES")
        }
        val patterns = RecurringDetector.detect(facts, today, classify)
        return Result(patterns, RecurringLearningStatus(if (result.personalUsed) "PERSONAL_MODEL" else if (result.model != null) "SEED_MODEL" else "RULES",
            result.reason, result.trainingSeries, result.validationSeries), examples,
            series.mapValues { it.value.ruleLabel })
    }
}
