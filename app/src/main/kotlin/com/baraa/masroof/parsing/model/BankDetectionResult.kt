package com.baraa.masroof.parsing.model

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence

/**
 * Result of bank detection from sender/body evidence.
 */
sealed interface BankDetectionResult {
    data class Detected(
        val bank: Bank,
        val confidence: Confidence,
        val evidence: List<String>,
    ) : BankDetectionResult

    data class Unknown(
        val reasons: List<String> = emptyList(),
    ) : BankDetectionResult

    /**
     * Sender or body looks like this bank, but not enough to parse.
     * Evidence only — no bank identity and no permission to run the parser.
     */
    data class Suspected(
        val evidence: List<String>,
        val reasons: List<String> = listOf("suspected_bank_sender"),
    ) : BankDetectionResult
}
