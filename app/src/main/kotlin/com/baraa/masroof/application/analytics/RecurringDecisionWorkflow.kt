package com.baraa.masroof.application.analytics

import com.baraa.masroof.data.analytics.AnalyticsJson
import com.baraa.masroof.data.analytics.RecurringFeedbackRow
import com.baraa.masroof.data.repository.RecurringAnswerStore
import com.baraa.masroof.analytics.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/** [featureVersion] is a persisted key from the removed recurrence model; stored rows keep it. */
@Serializable data class RecurringFeedbackPayload(val seriesKey: String, val sourceIds: List<String>,
    val label: String? = null, val featureVersion: String = FEATURE_VERSION) {
    fun validate() {
        require(seriesKey.isNotBlank() && sourceIds.size >= 2 && sourceIds.size <= 5000 && sourceIds.distinct().size == sourceIds.size)
        require(featureVersion == FEATURE_VERSION)
        label?.let { RecurringLabel.valueOf(it) }
    }
    companion object { const val FEATURE_VERSION = "recurring-numeric-v1" }
}

/** Recurring payments come from the rules; the user's explicit decision for a series overrides its cadence. */
internal object RecurringDecisionWorkflow {
    const val version = "recurring-rules-v1"

    /**
     * The answers in force, after promoting each `CONFIRM_PENDING` whose saved commitment now has its cadence: the
     * promotion appends a `CONFIRMED` answer with the pending one's payload and decidedAt.
     */
    suspend fun promotePending(answers: RecurringAnswerStore, committedCadences: Map<String, String>): List<RecurringFeedbackRow> =
        answers.effective().map { row ->
            if (row.kind != "CONFIRM_PENDING") row else {
                val payload = AnalyticsJson.format.decodeFromString<RecurringFeedbackPayload>(row.payload)
                if (payload.label != null && committedCadences[payload.sourceIds.last()] == payload.label) {
                    row.copy(kind = "CONFIRMED").also { answers.record(it) }
                } else row
            }
        }

    /** A "confirmed" decision counts only while its saved commitment still has that cadence. */
    fun build(facts: List<FinancialFact>, feedback: List<RecurringFeedbackRow>, today: Long,
        committedCadences: Map<String, String> = emptyMap()): List<RecurringPattern> {
        val overrides = feedback.mapNotNull { row ->
            if (row.kind == "CONFIRM_PENDING") return@mapNotNull null
            val payload = runCatching { AnalyticsJson.format.decodeFromString<RecurringFeedbackPayload>(row.payload).also { it.validate() } }.getOrNull()
                ?: return@mapNotNull null
            if (payload.seriesKey != row.seriesKey) return@mapNotNull null
            if (row.kind == "CONFIRMED" && committedCadences[payload.sourceIds.last()] != payload.label) return@mapNotNull null
            payload.label?.let { row.seriesKey to RecurringLabel.valueOf(it) }
        }.toMap()
        return RecurringDetector.detect(facts, today, overrides)
    }
}
