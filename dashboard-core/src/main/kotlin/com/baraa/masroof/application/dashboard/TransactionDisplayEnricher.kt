package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Fills display-only gaps on persisted transactions from linked ParsedEvent rows.
 *
 * Does not write back to Room — reconciliation may still persist corrections separately.
 */
object TransactionDisplayEnricher {
    fun enrichMerchants(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
    ): List<FinancialTransaction> {
        if (transactions.isEmpty() || parsedRecords.isEmpty()) return transactions
        val parsedByEventId = parsedRecords.associateBy { it.event.id }
        return transactions.map { tx ->
            if (!tx.merchant.isNullOrBlank()) return@map tx
            val merchant = tx.linkedParsedEventIds
                .asSequence()
                .mapNotNull { parsedByEventId[it]?.event?.merchant }
                .firstOrNull { it.isNotBlank() }
                ?: return@map tx
            tx.copy(merchant = merchant)
        }
    }
}
