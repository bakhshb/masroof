package com.baraa.masroof.application.interpretation

import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.ReviewCorrectionClosure
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/** Compare the evidence actually assembled against current decisions in the SAME write transaction. */
class AutomaticInterpretationWriteGuard(
    private val events: EffectiveParsedEventProvider,
    private val reviews: ReviewRepository,
    private val decisions: UserDecisionRepository,
    private val transactions: FinancialTransactionRepository,
    private val inferenceCurrent: suspend (ParsedEventRecord) -> Boolean,
    private val inTransaction: suspend (suspend () -> FinancialTransactionSaveResult) -> FinancialTransactionSaveResult,
    private val patternsCurrent: suspend (ParsedEventRecord, Boolean) -> Boolean =
        { record, _ -> !PatternInterpretationWritePolicy.isPatternDerived(record) },
) {
    suspend fun write(expected: List<ParsedEventRecord>, mutation: suspend () -> FinancialTransactionSaveResult): FinancialTransactionSaveResult = inTransaction {
        var reason = "stale_family_inference"
        val stale = expected.firstOrNull { prior ->
            val id=prior.event.rawSmsId
            val decided=decisions.effectiveFor(id)
            val current=events.findEffectiveByRawSmsId(id)
            val linked=transactions.isRawSmsLinked(id)
            val protected=ReviewCorrectionClosure.protects(decided, reviews.findByRawSmsId(id)) && linked
            if (protected || current != prior) true
            else if (current.automationConfirmed || decided.confirmsFinancial) false
            else if (!patternsCurrent(current, linked)) { reason = "stale_pattern_definition"; true }
            // Posted evidence is never re-read, so a newer model must not stop it from pairing with its other leg.
            else !linked && !inferenceCurrent(current)
        }
        if(stale != null) FinancialTransactionSaveResult.Conflict(stale.event.rawSmsId,reason)
        else mutation()
    }
}
