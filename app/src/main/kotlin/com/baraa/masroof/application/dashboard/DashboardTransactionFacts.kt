package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdParser
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType

/**
 * Per-transaction facts the dashboard rows display, interpreted once in the application layer
 * so presentation only formats them.
 */
data class DashboardTransactionFacts(
    /** Card container last4, else the primary card from parsed SMS card refs. */
    val primaryCardLast4: String?,
    /** [FinancialTransactionType.LOAN_REPAYMENT] for SMS-attributed loan repayments, else the stored type. */
    val effectiveType: FinancialTransactionType,
    /** Foreign amount × applied exchange rate (no international fee), when a rate is applied. */
    val sarEquivalent: Money?,
)

object DashboardTransactionFactsBuilder {
    /**
     * Facts keyed by transaction id. [cardInvolvement] holds card keys (`bankId:last4`) and
     * [loanInvolvement] the SMS-attributed loan repayments (`LoanRepaymentAttribution`).
     */
    fun build(
        transactions: List<FinancialTransaction>,
        cardInvolvement: Map<String, Set<String>>,
        loanInvolvement: Map<String, Set<String>>,
    ): Map<String, DashboardTransactionFacts> =
        transactions.associate { tx -> tx.id to facts(tx, cardInvolvement, loanInvolvement) }

    private fun facts(
        tx: FinancialTransaction,
        cardInvolvement: Map<String, Set<String>>,
        loanInvolvement: Map<String, Set<String>>,
    ): DashboardTransactionFacts {
        val containerCardLast4 = FinancialContainerIdParser.cardLast4FromContainers(
            sourceContainerId = tx.sourceContainerId,
            destinationContainerId = tx.destinationContainerId,
        )
        val parsedCardLast4 = CardTransactionInvolvementResolver
            .resolvePrimaryCardKey(tx, cardInvolvement)
            ?.substringAfter(':', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }
        val rate = tx.appliedExchangeRate
        val sarEquivalent = if (tx.amount.currency.convertsToSar() && rate != null) {
            ForeignPurchaseSarConverter.foreignToSar(
                foreignAmount = tx.amount,
                exchangeRate = rate,
                internationalFee = null,
                targetCurrency = Currency.SAR,
            )
        } else {
            null
        }
        return DashboardTransactionFacts(
            primaryCardLast4 = containerCardLast4 ?: parsedCardLast4,
            effectiveType = if (tx.id in loanInvolvement) FinancialTransactionType.LOAN_REPAYMENT else tx.type,
            sarEquivalent = sarEquivalent,
        )
    }
}
