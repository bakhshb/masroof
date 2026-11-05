package com.baraa.masroof.application.interpretation

import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository

data class LearningInsights(val pending: Boolean = false, val attempted: Int = 0, val suggested: Int = 0,
    val understood: Int = 0, val posted: Int = 0, val awaitingReview: Int = 0,
    val learned: Int = 0, val blocked: Int = 0, val excluded: Int = 0,
    val repeatedContradictions: Int = 0, val exclusions: Map<String,Int> = emptyMap(), val pendingDecisions: Int = 0,
    val templateSuggestions: Int = 0, val suggesting: Boolean = false)

/** Operational counts have denominators. No correction does not prove a prediction correct. */
class LearningInsightsWorkflow(private val learning: BankInterpretationService?,
    private val parsed: ParsedEventRepository, private val transactions: FinancialTransactionRepository,
    private val reviews: ReviewRepository) {
    suspend fun load(): LearningInsights {
        val service = learning ?: return LearningInsights(pending = true)
        val audits = service.interpretations()
        val feedback = service.learningFeedback()
        val labels = service.learningLabels()
        val ids = audits.filter { it.origin in setOf("ML", "HUMAN_TEMPLATE") }.map { it.rawId }.toSet()
        val understood = parsed.listByRawSmsIds(ids).filter { record ->
            record.event.confidence.reasons.any { it.startsWith("family_origin:") } && record.event.messageFamily.name != "UNKNOWN"
        }.map { it.event.rawSmsId }.toSet()
        val posted = understood.count { transactions.isRawSmsLinked(it) }
        val required = reviews.listRequired().count { it.rawSmsId in understood }
        val repeat = audits.filter { audit -> audit.origin in setOf("ML","HUMAN_TEMPLATE") && labels.any { label ->
            label.bankId == audit.bankId && label.template == audit.template && label.sourceAt < audit.interpretedAt && label.family != audit.family
        } }.map { it.rawId }.distinct().size
        val effective = feedback.filter { it.reason != "SUPERSEDED" }
        val excluded = effective.filter { it.state == LearningFeedbackState.EXCLUDED.name }
        val blocked = effective.filter { it.state == "BLOCKED" }.mapNotNull { it.template }.toSet()
        return LearningInsights(service.modelVersion == null, audits.filter { it.assistRequested }.map { it.rawId }.distinct().size,
            ids.size,understood.size,posted,required,labels.filter { it.template !in blocked }.map { it.rawId }.distinct().size,blocked.size,
            excluded.size,repeat,excluded.groupingBy { it.reason ?: "AMBIGUOUS_FAMILY" }.eachCount(),effective.count { it.state == "PENDING" },
            audits.filter { it.origin == "HUMAN_TEMPLATE" }.map { it.rawId }.distinct().size,
            service.suggesting)
    }
}
