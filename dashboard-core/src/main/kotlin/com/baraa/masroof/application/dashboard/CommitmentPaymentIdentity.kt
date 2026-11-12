package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/** Stable persisted evidence, separate from the editable commitment display label. */
data class CommitmentPaymentIdentity(val names: Set<String>, val billerCodes: Set<String> = emptySet()) {
    fun matches(other: CommitmentPaymentIdentity): Boolean =
        if (billerCodes.isNotEmpty() && other.billerCodes.isNotEmpty()) {
            billerCodes.size == 1 && other.billerCodes.size == 1 && billerCodes == other.billerCodes
        } else names.intersect(other.names).isNotEmpty()

    companion object {
        /** The dashboard's merchant key, so a commitment matches the merchant the dashboard groups. */
        fun normalize(value: String): String = MerchantNameNormalizer.key(value)
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
