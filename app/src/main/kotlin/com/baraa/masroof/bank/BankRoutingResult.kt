package com.baraa.masroof.bank

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.model.BankDetectionResult

/**
 * Outcome of routing one SMS across every registered bank adapter.
 */
sealed interface BankRoutingResult {
    /** Exactly one adapter credibly claims the SMS. */
    data class Matched(
        val adapter: BankSmsAdapter,
        val detection: BankDetectionResult.Detected,
    ) : BankRoutingResult

    data class NotMatched(
        val reason: String,
    ) : BankRoutingResult

    /**
     * More than one adapter claims the SMS. Callers must not pick one by list order
     * and must not parse with any of the [candidates]; the evidence is bank-like and
     * fails safe to review instead.
     */
    data class Ambiguous(
        val candidates: List<Matched>,
    ) : BankRoutingResult {
        init {
            require(candidates.size > 1) { "Ambiguous route needs at least two candidates" }
        }

        val banks: List<Bank> get() = candidates.map { it.adapter.bank }

        val reason: String get() = REASON_AMBIGUOUS_BANK_ROUTE
    }

    companion object {
        const val REASON_AMBIGUOUS_BANK_ROUTE = "ambiguous_bank_route"
    }
}
