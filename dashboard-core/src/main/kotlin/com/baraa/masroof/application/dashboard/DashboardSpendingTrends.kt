package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.FinancialTransaction

/** Precomputed display scopes. No presentation code sums or classifies money. */
data class DashboardSpendingTrends(
    val overall: DailySpendingTrend,
    val accounts: DailySpendingTrend,
    val creditCards: DailySpendingTrend,
    val byAccount: Map<String, DailySpendingTrend>,
    val byCard: Map<String, DailySpendingTrend>,
    val debitCards: DailySpendingTrend? = null,
    val purchases: DailySpendingTrend,
    /** Scope before FX resolution: unresolved eligible purchases are missing data, not out-of-scope rows. */
    val purchaseScopeTransactionIds: Set<String> = purchases.transactionIdsByDate.values.flatten().toSet(),
)

object DashboardSpendingTrendsBuilder {
    fun build(
        context: DashboardProjectionContext,
        accounts: AccountsDashboardSection,
        cards: CardsDashboardSection,
    ): DashboardSpendingTrends = with(context) {
        fun trend(rows: List<FinancialTransaction>, outflows: Boolean = false) =
            DailySpendingTrendBuilder.build(
                period = period,
                transactions = rows.distinctBy { it.id },
                parsedRecords = parsedRecords,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents,
                zoneId = zoneId,
                today = today,
                includeFinancialOutflows = outflows,
            )

        val cardRows = linkedMapOf<String, List<FinancialTransaction>>()
        val creditKeys = mutableSetOf<String>()
        val debitKeys = mutableSetOf<String>()
        cards.creditFacilities.facilities.flatMap { it.allCards }.forEach { card ->
            val key = CardTransactionInvolvementResolver.cardKey(card.bank.id, card.last4)
            val container = FinancialContainerIdFactory.cardId(card.bank, card.last4)
            creditKeys.add(key)
            cardRows[key] = transactions.filter { transaction ->
                key in cards.transactionCardInvolvement[transaction.id].orEmpty() ||
                    transaction.sourceContainerId == container || transaction.destinationContainerId == container
            }
        }
        cards.creditFacilities.debitCards.forEach { card ->
            val key = CardTransactionInvolvementResolver.cardKey(card.bank.id, card.last4)
            debitKeys.add(key)
            cardRows[key] = transactions.filter { transaction ->
                key in cards.transactionDebitSpendInvolvement[transaction.id].orEmpty()
            }
        }
        val cardTransactionIds = cardRows.values.flatten().map { it.id }.toSet()
        val creditIds = creditKeys.flatMap { cardRows[it].orEmpty() }.map { it.id }.toSet()
        val debitIds = debitKeys.flatMap { cardRows[it].orEmpty() }.map { it.id }.toSet()
        fun configurable(rows: List<FinancialTransaction>, allowed: Set<ChartDataCategory>, outflows: Boolean = false) =
            trend(rows, outflows).copy(categoryTrends = ChartCategoryTrendsBuilder.build(context, rows,
                outflows, allowed, creditIds, debitIds))
        val parsedById = parsedRecords.associateBy { it.event.id }
        val purchaseRows = transactions.filter {
            DailyPurchasesScope.includes(it, it.id in cardTransactionIds, parsedById)
        }
        val accountTrends = AccountDailySpendingTrendsBuilder.build(context, accounts.transactionAccountInvolvement, creditKeys,
            cards.transactionCardInvolvement)
        DashboardSpendingTrends(
            overall = configurable(transactions, ChartCategoryTrendsBuilder.overall, outflows = true),
            accounts = accountTrends.aggregate,
            creditCards = configurable(creditKeys.flatMap { cardRows[it].orEmpty() }, ChartCategoryTrendsBuilder.credit),
            byAccount = accountTrends.byAccount,
            byCard = cardRows.mapValues { (key, rows) -> configurable(rows,
                if (key in debitKeys) ChartCategoryTrendsBuilder.debit else ChartCategoryTrendsBuilder.credit,
                outflows = key in debitKeys) },
            debitCards = configurable(debitKeys.flatMap { cardRows[it].orEmpty() }, ChartCategoryTrendsBuilder.debit, outflows = true),
            purchases = configurable(purchaseRows, ChartCategoryTrendsBuilder.purchases, outflows = true),
            purchaseScopeTransactionIds = purchaseRows.map { it.id }.toSet(),
        )
    }
}
