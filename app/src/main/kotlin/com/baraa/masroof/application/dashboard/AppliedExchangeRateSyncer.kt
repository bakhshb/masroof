package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction

/**
 * Applies resolved exchange rates to transactions that have no persisted rate yet.
 *
 * Pure: the dashboard shows the in-memory SAR equivalent, and persistence belongs to
 * `ExchangeRateEnrichmentWorkflow`. A transaction that already carries a rate and source
 * keeps them, so persisted values always win over a fresh resolution.
 */
object AppliedExchangeRateSyncer {
    fun applyInMemory(
        transactions: List<FinancialTransaction>,
        resolutions: Map<String, SarEquivalentResolution>,
    ): List<FinancialTransaction> {
        if (resolutions.isEmpty()) return transactions
        return transactions.map { tx ->
            val resolution = resolutions[tx.id]
            if (resolution == null || !needsAppliedExchangeRate(tx)) {
                tx
            } else {
                tx.copy(
                    appliedExchangeRate = resolution.exchangeRate,
                    exchangeRateSource = resolution.source,
                )
            }
        }
    }

    fun needsAppliedExchangeRate(transaction: FinancialTransaction): Boolean =
        transaction.appliedExchangeRate == null || transaction.exchangeRateSource == null
}
