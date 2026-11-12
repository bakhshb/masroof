package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction

/**
 * Replaces an incomplete exchange-rate pair for display.
 *
 * Pure: the dashboard shows the in-memory SAR equivalent, and persistence belongs to
 * `ExchangeRateEnrichmentWorkflow`. A complete stored pair stays. A row missing either
 * half takes the rate and source from the same resolution, so an old number is never
 * shown beside a newly inferred source.
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
