package com.baraa.masroof.parsing.validator

import java.time.Duration
import java.time.LocalDateTime

/**
 * Explicit thresholds a financial draft must meet before [com.baraa.masroof.parsing.finalize.ParseFinalizer]
 * may emit SUCCESS. Anything below them is finalized as REVIEW_REQUIRED, never dropped.
 *
 * [maxFutureSkew] tolerates the SMS local time zone being ahead of the device clock's UTC.
 */
data class AutomaticUsePolicy(
    val minFinancialConfidence: Double = DEFAULT_MIN_FINANCIAL_CONFIDENCE,
    val earliestOccurredAtLocal: LocalDateTime = DEFAULT_EARLIEST_OCCURRED_AT_LOCAL,
    val maxFutureSkew: Duration = DEFAULT_MAX_FUTURE_SKEW,
) {
    init {
        require(minFinancialConfidence in 0.0..1.0) {
            "minFinancialConfidence must be in [0.0, 1.0], was $minFinancialConfidence"
        }
        require(!maxFutureSkew.isNegative) { "maxFutureSkew must not be negative" }
    }

    companion object {
        const val DEFAULT_MIN_FINANCIAL_CONFIDENCE = 0.8
        val DEFAULT_EARLIEST_OCCURRED_AT_LOCAL: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)
        val DEFAULT_MAX_FUTURE_SKEW: Duration = Duration.ofDays(2)
    }
}
