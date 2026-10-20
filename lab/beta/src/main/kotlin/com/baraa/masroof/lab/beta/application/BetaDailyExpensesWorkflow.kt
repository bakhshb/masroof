package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import java.time.LocalDate

internal data class BetaDailyExpenseSelection(val source: SpendingTrend,val selected: SpendingTrend,
    val point: DailySpending?,val transactions: List<TransactionFacts>,val chartKey: String,val complete: Boolean)

/** Source DailyExpensesScreen selection over persisted projections; never parses messages or writes finances. */
internal object BetaDailyExpensesWorkflow {
    fun pageStateKey(route: String,bank: String?,id: String?) = listOf(route,bank.orEmpty(),id.orEmpty())
        .joinToString("|") {"${it.length}:$it"}
    /** The approved "all daily expenses" chart includes cash, external outflows and loan repayments. */
    fun homeChoice(storedScope: TrendScope) = BetaChartChoice(
        if(storedScope==TrendScope.PURCHASES) TrendScope.PURCHASES else TrendScope.FINANCIAL_OUTFLOWS)

    fun select(snapshot: DashboardSnapshot,preferences: BetaChartDataWorkflow,asset: AssetId?,date: LocalDate?): BetaDailyExpenseSelection? {
        val choice=if(asset==null) BetaChartChoice(TrendScope.FINANCIAL_OUTFLOWS) else BetaChartChoice(TrendScope.ASSET,asset=asset)
        if(asset!=null && snapshot.debitCards.none {it.cardId==asset} &&
            (snapshot.creditFacilities.flatMap {it.cards}+snapshot.standaloneCreditCards).none {it.cardId==asset}) return null
        val source=BetaDashboardExplorer.trend(snapshot,choice) ?: return null
        val key=BetaChartDataWorkflow.key(choice.scope,choice.asset)
        val selected=preferences.selected(source,key)
        val point=selected.points.singleOrNull {it.date==date} ?: selected.points.lastOrNull()
        val ids=point?.transactions.orEmpty()
        val rows=snapshot.transactions.filter {it.key in ids}.sortedWith(
            compareByDescending<TransactionFacts> {it.time.instant})
        val hasMoney=point?.let {listOfNotNull(it.net,it.beforeRefunds,it.refunds).any {value -> value.amount.signum()!=0}}==true
        return BetaDailyExpenseSelection(source,selected,point,rows,key,point?.net!=null && (!hasMoney || ids.isNotEmpty()) &&
            ids.all {id -> rows.any {it.key==id}})
    }
    fun merchantComplete(snapshot: DashboardSnapshot,key: String): Boolean {
        val merchant=snapshot.analysis?.merchants?.singleOrNull {it.merchantKey==key} ?: return false
        val available=snapshot.transactions.mapTo(hashSetOf()) {it.key}
        return merchant.transactionIds.isNotEmpty() && merchant.transactionIds.all {it in available}
    }
    fun merchantTransactions(snapshot: DashboardSnapshot,key: String): List<TransactionFacts> {
        val ids=snapshot.analysis?.merchants?.singleOrNull {it.merchantKey==key}?.transactionIds ?: return emptyList()
        return snapshot.transactions.filter {it.key in ids}.sortedWith(
            compareByDescending<TransactionFacts> {it.time.instant})
    }
}
