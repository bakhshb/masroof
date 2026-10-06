package com.baraa.masroof.bank

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.model.BankDetectionResult

/**
 * Routes incoming SMS by evaluating every registered bank adapter.
 *
 * Exactly one [BankDetectionResult.Detected] adapter → [BankRoutingResult.Matched];
 * more than one → [BankRoutingResult.Ambiguous] (candidates sorted by bank id, so
 * registration order never decides the bank); none → [BankRoutingResult.NotMatched].
 */
class BankSmsRegistry(
    private val adapters: List<BankSmsAdapter>,
) {
    fun route(sender: String, body: String): BankRoutingResult {
        val matches = mutableListOf<BankRoutingResult.Matched>()
        var unmatchedReason: String? = null
        for (adapter in adapters) {
            when (val detection = adapter.detect(sender, body)) {
                is BankDetectionResult.Detected ->
                    matches += BankRoutingResult.Matched(adapter, detection)
                is BankDetectionResult.Unknown ->
                    unmatchedReason = detection.reasons.firstOrNull() ?: unmatchedReason
            }
        }
        return when (matches.size) {
            0 -> BankRoutingResult.NotMatched(reason = unmatchedReason ?: "sender_not_in_scope")
            1 -> matches.single()
            else -> BankRoutingResult.Ambiguous(candidates = matches.sortedBy { it.adapter.bank.id })
        }
    }

    fun adapterFor(bank: Bank): BankSmsAdapter? =
        adapters.firstOrNull { it.bank == bank }

    fun singleAdapterOrNull(): BankSmsAdapter? =
        adapters.singleOrNull()
}
