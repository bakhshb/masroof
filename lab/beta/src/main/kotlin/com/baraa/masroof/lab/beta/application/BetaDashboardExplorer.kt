package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.PostingKey
import java.math.RoundingMode
import java.time.LocalDate

/** Presentation selection over authoritative facts, never a financial aggregation. */
data class BetaChartChoice(val scope: TrendScope = TrendScope.OVERALL,
    val category: ChartCategory? = null, val asset: AssetId? = null)

object BetaDashboardExplorer {
    fun trend(snapshot: DashboardSnapshot, choice: BetaChartChoice): SpendingTrend? =
        snapshot.analysis?.trends?.singleOrNull { it.scope == choice.scope && it.category == choice.category && it.assetId == choice.asset }

    fun transactions(snapshot: DashboardSnapshot, choice: BetaChartChoice? = null,
        asset: AssetId? = null, day: LocalDate? = null): List<TransactionFacts> {
        val ids = choice?.let { trend(snapshot, it) }?.points?.flatMap { it.transactions }?.toSet()
        if (choice != null && ids == null) return emptyList()
        val facilityCards = snapshot.creditFacilities.singleOrNull { it.facilityId == asset }?.cards?.map { it.cardId }.orEmpty()
        return snapshot.transactions.filter { tx ->
            (ids == null || tx.key in ids) &&
                (asset == null || asset in tx.accounts || asset in tx.cards || asset in tx.loans || asset == tx.source || asset == tx.destination || tx.cards.any { it in facilityCards }) &&
                (day == null || tx.time.instant.atZone(snapshot.query.zone).toLocalDate() == day)
        }.sortedWith(compareByDescending<TransactionFacts> { it.time.instant })
    }

    fun filterTransactions(rows: List<TransactionFacts>, search: String, type: String?, asset: AssetId?): List<TransactionFacts> {
        val text=search.trim()
        return rows.filter {tx ->
            (type==null || tx.effectiveType.name==type) &&
            (asset==null || asset in tx.accounts || asset in tx.cards || asset in tx.debitSpendingCards || asset in tx.loans || tx.source==asset || tx.destination==asset) &&
            (text.isEmpty() || listOfNotNull(tx.merchant,tx.counterparty,tx.primaryCardLast4,tx.amount.amount.toPlainString()).any {it.contains(text,true)})
        }.sortedWith(compareByDescending<TransactionFacts> {it.time.instant})
    }

    fun transaction(snapshot: DashboardSnapshot, key: PostingKey): TransactionFacts? = snapshot.transactions.singleOrNull { it.key == key }

    /** Comparison-window provenance may be outside the selected-month transaction read. */
    fun canDrillDown(snapshot: DashboardSnapshot, choice: BetaChartChoice, day: LocalDate): Boolean {
        val point = trend(snapshot, choice)?.points?.singleOrNull { it.date == day } ?: return false
        val available = snapshot.transactions.mapTo(hashSetOf()) { it.key }
        return point.net != null && point.transactions.all { it in available }
    }
}
