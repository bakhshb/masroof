package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.*

/** Selector contracts over authoritative read facts; financial extraction/posting has separate cross-run tests. */
class BetaDailyExpensesWorkflowTest {
    private val query=DashboardQuery(Environment.BETA,Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-10-03T00:00:00Z"),ZoneOffset.UTC,Currency.SAR)
    private val period=DisplayPeriod(query.from,query.until,query.zone,PeriodKind.SELECTED,null)
    private val day=LocalDate.of(2026,10,1)
    private val account=AssetId(Environment.BETA,BankId("BANK_ALJAZIRA"),"account")
    private fun amount(value: String)=SignedAmount(BigDecimal(value),Currency.SAR)
    private fun key(id: String)=PostingKey(Environment.BETA,id)
    private fun row(id: String,type: FinancialType,value: String,card: AssetId?=null)=TransactionFacts(key(id),PostingRevision(0,1),
        TransactionTime(query.from.plusSeconds(60),query.zone),type,type,Money.of(value,Currency.SAR),"Same merchant",null,null,
        if(card==null) setOf(account) else emptySet(),setOfNotNull(card),emptySet(),emptySet(),emptySet(),null,null,null,emptySet(),card ?: account)
    private fun trend(scope: TrendScope,value: String,ids: Set<PostingKey>,asset: AssetId?=null,category: ChartCategory?=null)=SpendingTrend(
        asset,category,period,Currency.SAR,listOf(DailySpending(day,amount(value),amount(value),amount("0"),ids)),amount(value),scope,
        amount(value),amount(value),amount("0"))
    private fun snapshot(rows: List<TransactionFacts>,trends: List<SpendingTrend>,cards: List<CreditCardSummary> = emptyList(),merchants: List<MerchantSpending> = emptyList())=
        DashboardSnapshot(query,listOf(AccountSummary(account,null)),emptyList(),emptyList(),standaloneCreditCards=cards,transactions=rows,
            analysis=AnalysisSummary(Currency.SAR,SummaryMetric.entries.associateWith {null},rows.size,0,0,trends,merchants))
    private fun workflow()=BetaChartDataWorkflow(InMemoryBetaChartDataPreferencesRepository())

    @Test fun allDailyChoiceIncludesCashAndLoanWhileLeavingOrdinarySeriesUntouched() {
        val rows=listOf(row("purchase",FinancialType.EXPENSE,"10"),row("cash",FinancialType.CASH_WITHDRAWAL,"20"),row("loan",FinancialType.LOAN_REPAYMENT,"80"))
        val ordinary=trend(TrendScope.OVERALL,"10",setOf(key("purchase")))
        val all=trend(TrendScope.FINANCIAL_OUTFLOWS,"110",rows.map {it.key}.toSet())
        val read=snapshot(rows,listOf(ordinary,all))
        assertSame(all,BetaDashboardExplorer.trend(read,BetaDailyExpensesWorkflow.homeChoice(TrendScope.OVERALL)))
        val chosen=BetaDailyExpensesWorkflow.select(read,workflow(),null,day)!!
        assertEquals(amount("110"),chosen.point!!.net)
        assertEquals(rows.map {it.key}.toSet(),chosen.transactions.map {it.key}.toSet())
        assertTrue(chosen.complete);assertEquals(amount("10"),ordinary.totalSpending)
        assertEquals(rows.map {it.key},chosen.transactions.map {it.key}) // Stable source order for equal bank timestamps.
    }
    @Test fun dailyCardSelectionUsesTheBankQualifiedIdentityInsteadOfMatchingSuffixes() {
        val a=account.copy(localId="card-7271")
        val b=a.copy(bank=BankId("OTHER_BANK"))
        val rows=listOf(row("a",FinancialType.EXPENSE,"10",a),row("b",FinancialType.EXPENSE,"20",b))
        fun card(id: AssetId)=CreditCardSummary(id,CreditCardRole.STANDALONE,null,"7271",null,null,null,null,null)
        val read=snapshot(rows,listOf(trend(TrendScope.ASSET,"10",setOf(key("a")),a),trend(TrendScope.ASSET,"20",setOf(key("b")),b)),listOf(card(a),card(b)))
        val selected=BetaDailyExpensesWorkflow.select(read,workflow(),b,day)!!
        assertEquals(amount("20"),selected.point!!.net);assertEquals(listOf(key("b")),selected.transactions.map {it.key})
        assertNull(BetaDailyExpensesWorkflow.select(read,workflow(),b.copy(localId="unknown-7271"),day))
    }
    @Test fun customDayAndMerchantDrillDownFollowProvenanceAndKeepMissingRowsUnavailable() {
        val rows=listOf(row("purchase-a",FinancialType.EXPENSE,"10"),row("purchase-b",FinancialType.EXPENSE,"5"),
            row("fee",FinancialType.FEE,"2"),row("refund",FinancialType.REFUND,"3"))
        val purchases=trend(TrendScope.FINANCIAL_OUTFLOWS,"15",setOf(key("purchase-a"),key("purchase-b")),category=ChartCategory.OTHER_EXPENSES)
        val fee=trend(TrendScope.FINANCIAL_OUTFLOWS,"2",setOf(key("fee")),category=ChartCategory.FEES)
        val refund=trend(TrendScope.FINANCIAL_OUTFLOWS,"-3",setOf(key("refund")),category=ChartCategory.UNATTRIBUTED_REFUNDS)
            .let {it.copy(points=it.points.map {p -> p.copy(beforeRefunds=amount("0"),refunds=amount("3"))},totalBeforeRefunds=amount("0"),totalRefunds=amount("3"))}
        val full=trend(TrendScope.FINANCIAL_OUTFLOWS,"14",rows.map {it.key}.toSet())
        val all=full.copy(points=full.points.map {it.copy(beforeRefunds=amount("17"),refunds=amount("3"))},
            totalBeforeRefunds=amount("17"),totalRefunds=amount("3"),categoryTrends=mapOf(
            ChartCategory.OTHER_EXPENSES to purchases,ChartCategory.FEES to fee,ChartCategory.UNATTRIBUTED_REFUNDS to refund))
        val merchant=MerchantSpending("Same merchant",amount("12"),2,"same",setOf(key("purchase-a"),key("purchase-b"),key("refund")))
        val read=snapshot(rows,listOf(all),merchants=listOf(merchant))
        val preferences=workflow();preferences.save(BetaChartDataWorkflow.key(TrendScope.FINANCIAL_OUTFLOWS,null),setOf(ChartCategory.UNATTRIBUTED_REFUNDS),all.categoryTrends.keys)
        val selected=BetaDailyExpensesWorkflow.select(read,preferences,null,day)!!
        assertEquals(amount("-3.00"),selected.point!!.net);assertEquals(listOf(key("refund")),selected.transactions.map {it.key})
        assertEquals(merchant.transactionIds,BetaDailyExpensesWorkflow.merchantTransactions(read,"same").map {it.key}.toSet())
        assertTrue(BetaDailyExpensesWorkflow.merchantComplete(read,"same"))
        assertFalse(BetaDailyExpensesWorkflow.merchantComplete(read.copy(transactions=rows.dropLast(1)),"same"))
        assertFalse(BetaDailyExpensesWorkflow.merchantComplete(read.copy(analysis=read.analysis!!.copy(merchants=listOf(merchant.copy(transactionIds=emptySet())))),"same"))
        assertEquals(amount("14"),all.totalSpending)
        val unavailable=all.copy(categoryTrends=emptyMap(),points=all.points.map {it.copy(transactions=setOf(key("missing")))})
        assertFalse(BetaDailyExpensesWorkflow.select(read.copy(analysis=read.analysis!!.copy(trends=listOf(unavailable))),workflow(),null,day)!!.complete)
        val noProvenance=unavailable.copy(points=unavailable.points.map {it.copy(transactions=emptySet())})
        assertFalse(BetaDailyExpensesWorkflow.select(read.copy(analysis=read.analysis!!.copy(trends=listOf(noProvenance))),workflow(),null,day)!!.complete)
    }
}
