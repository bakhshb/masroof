package com.baraa.masroof.application.ingestion

import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.validator.ValidationFinding

/**
 * Correctness-blocking work after a ParsedEvent is already durable.
 * Exchange-rate enrichment is not one of these stages.
 */
enum class DerivedProcessingStage {
    OWNERSHIP_DISCOVERY,
    RECONCILIATION,
    REVIEW_UPDATE,
}

/**
 * Explicit outcome of ingesting one [com.baraa.masroof.domain.model.RawSms] / provider SMS.
 * Expected duplicate / unsupported cases are not exceptions.
 *
 * Recognized-bank outcomes without an automatically usable ParsedEvent
 * ([Unsupported], [Invalid], [ReviewRequired] with `event == null`, and [Failed]
 * after RawSms persistence) are backed by a durable REQUIRED review row keyed by
 * rawSmsId (see [com.baraa.masroof.application.review.IngestionReviewService]).
 */
sealed interface SmsIngestionResult {
    /** Already present as RawSms evidence; parsing not re-run. */
    data object Duplicate : SmsIngestionResult

    /** No registered bank adapter matched; nothing persisted. */
    data class NotRelevant(
        val reason: String,
    ) : SmsIngestionResult

    data class Parsed(
        val rawSmsId: String,
        val event: ParsedEvent,
        val details: ParsedEventDetails,
    ) : SmsIngestionResult

    /**
     * Parse evidence is durable, but ownership discovery, reconciliation, or review
     * refresh failed. Live processing retries this. It is not an exchange-rate failure.
     */
    data class DerivedIncomplete(
        val rawSmsId: String,
        val event: ParsedEvent,
        val details: ParsedEventDetails,
        val stage: DerivedProcessingStage,
        val cause: Throwable? = null,
        /** Nonthrowing reconciliation failures. Null when the stage threw. */
        val failureCount: Int? = null,
    ) : SmsIngestionResult

    data class ReviewRequired(
        val rawSmsId: String,
        val event: ParsedEvent?,
        val details: ParsedEventDetails,
        val reasons: List<String>,
    ) : SmsIngestionResult

    data class NonFinancial(
        val rawSmsId: String,
        val event: ParsedEvent?,
        val details: ParsedEventDetails,
        val reason: String,
    ) : SmsIngestionResult

    /** Recognized bank, unsupported format. [rawSmsId] is the persisted evidence row. */
    data class Unsupported(
        val rawSmsId: String?,
        val reason: String,
    ) : SmsIngestionResult

    data class Invalid(
        val rawSmsId: String,
        val findings: List<ValidationFinding>,
    ) : SmsIngestionResult

    /**
     * Unexpected failure after RawSms may already be stored.
     * Evidence is intentionally retained.
     */
    data class Failed(
        val rawSmsId: String?,
        val message: String,
        val cause: Throwable? = null,
    ) : SmsIngestionResult
}
