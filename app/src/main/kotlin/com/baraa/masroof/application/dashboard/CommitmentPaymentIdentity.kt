package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.text.Normalizer
import java.util.Locale

/** Stable persisted evidence, separate from the editable commitment display label. */
data class CommitmentPaymentIdentity(val names: Set<String>, val billerCodes: Set<String> = emptySet()) {
    fun matches(other: CommitmentPaymentIdentity): Boolean =
        if (billerCodes.isNotEmpty() && other.billerCodes.isNotEmpty()) {
            billerCodes.size == 1 && other.billerCodes.size == 1 && billerCodes == other.billerCodes
        } else names.intersect(other.names).isNotEmpty()

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
            .trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")
        fun from(transaction: FinancialTransaction, records: Map<String, ParsedEventRecord> = emptyMap()): CommitmentPaymentIdentity {
            val facts = transaction.linkedParsedEventIds.mapNotNull { records[it]?.details }
            return CommitmentPaymentIdentity(
                (listOfNotNull(transaction.merchant, transaction.counterparty) + facts.mapNotNull { it.biller })
                    .map(::normalize).filter { it.isNotEmpty() }.toSet(),
                facts.mapNotNull { it.billerCode?.trim()?.takeIf(String::isNotEmpty) }.toSet(),
            )
        }
    }
}

enum class CommitmentMatchIssue { AMBIGUOUS_PAYMENT, MISSING_SOURCE }
