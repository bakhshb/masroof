package com.baraa.masroof.application.ingestion

import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository

/** Check and parse/store share a Room transaction, so concurrent human decisions cannot be overwritten. */
class PatternEvidenceProtection(private val reviews: ReviewRepository, private val decisions: UserDecisionRepository,
    private val transactions: FinancialTransactionRepository,
    private val parsed: ParsedEventRepository,
    private val inTransaction: suspend (suspend () -> SmsIngestionResult) -> SmsIngestionResult) {
    suspend fun run(rawSmsId: String, work: suspend () -> SmsIngestionResult, resume: suspend () -> SmsIngestionResult): SmsIngestionResult = inTransaction {
        val decided = decisions.effectiveFor(rawSmsId)
        // A settled review (an automatic settlement too) keeps its evidence like a decision does.
        val settled = reviews.findByRawSmsId(rawSmsId)?.resolutionKind != null
        if (decided.isNonFinancial) SmsIngestionResult.Duplicate
        else if (transactions.isRawSmsLinked(rawSmsId) ||
            ((settled || decided.corrections.isNotEmpty()) && parsed.findByRawSmsId(rawSmsId) != null)) resume()
        // An early correction is an overlay, not an existing ParsedEvent to resume. Initial extraction preserves it.
        else work()
    }
}
