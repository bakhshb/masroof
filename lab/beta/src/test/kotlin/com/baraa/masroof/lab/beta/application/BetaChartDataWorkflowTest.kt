package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.PostingKey
import com.baraa.masroof.lab.finance.value.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.*

class BetaChartDataWorkflowTest {
    private val period=DisplayPeriod(Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-10-03T00:00:00Z"),ZoneOffset.UTC,PeriodKind.SELECTED,null)
    private fun amount(value: String)=SignedAmount(BigDecimal(value),Currency.SAR)
    private fun part(category: ChartCategory,net: String?,gross: String?,refund: String?,id: String): SpendingTrend {
        val points=(0L..1L).map {i -> DailySpending(LocalDate.of(2026,10,1).plusDays(i),net?.let(::amount),gross?.let(::amount),refund?.let(::amount),setOf(PostingKey(Environment.BETA,"$id-$i")))}
        return SpendingTrend(null,category,period,Currency.SAR,points,null,scope=TrendScope.CREDIT_CARDS)
    }
    private fun trend(): SpendingTrend {
        val purchase=part(ChartCategory.CREDIT_PURCHASES,"100","100","0","purchase")
        val refund=part(ChartCategory.UNATTRIBUTED_REFUNDS,"-20","0","20","refund")
        return SpendingTrend(null,null,period,Currency.SAR,purchase.points.mapIndexed {i,p ->
            p.copy(net=amount("80"),beforeRefunds=amount("100"),refunds=amount("20"),transactions=p.transactions+refund.points[i].transactions)
        },amount("80"),TrendScope.CREDIT_CARDS,amount("160"),amount("200"),amount("40"),mapOf(
            ChartCategory.CREDIT_PURCHASES to purchase,ChartCategory.UNATTRIBUTED_REFUNDS to refund))
    }
    @Test fun categorySelectionKeepsLineAverageGrossRefundsAndDrillDownInOneScope() {
        val original=trend()
        val selected=BetaChartDataWorkflow.select(original,setOf(ChartCategory.UNATTRIBUTED_REFUNDS))
        assertEquals(amount("-20.00"),selected.averageDailySpending)
        assertEquals(amount("-40.00"),selected.totalSpending)
        assertEquals(amount("0.00"),selected.totalBeforeRefunds)
        assertEquals(amount("40.00"),selected.totalRefunds)
        assertEquals(listOf("-20.00","-20.00"),selected.points.map {it.net!!.amount.toPlainString()})
        assertEquals(setOf(PostingKey(Environment.BETA,"refund-0")),selected.points.first().transactions)
        assertSame(original,BetaChartDataWorkflow.select(original,original.categoryTrends.keys))
        assertEquals(amount("160"),original.totalSpending)
        assertEquals(2,original.points.first().transactions.size)
    }
    @Test fun unknownContributionNeverBecomesKnownZeroInCustomizedChart() {
        val original=trend()
        val unknown=part(ChartCategory.CREDIT_PURCHASES,null,null,null,"unavailable")
        val changed=original.copy(categoryTrends=original.categoryTrends+(ChartCategory.CREDIT_PURCHASES to unknown))
        val selected=BetaChartDataWorkflow.select(changed,setOf(ChartCategory.CREDIT_PURCHASES))
        assertNull(selected.totalSpending);assertNull(selected.averageDailySpending)
        assertTrue(selected.points.all {it.net==null && it.beforeRefunds==null && it.refunds==null})
        assertSame(changed,BetaChartDataWorkflow.select(changed,changed.categoryTrends.keys))
    }
    @Test fun saveResetAndFailureKeepDurableChoicesSeparateFromFinancialFacts() {
        val repo=InMemoryBetaChartDataPreferencesRepository()
        val workflow=BetaChartDataWorkflow(repo)
        val original=trend();val key=BetaChartDataWorkflow.key(TrendScope.CREDIT_CARDS,null)
        workflow.save(key,setOf(ChartCategory.CREDIT_PURCHASES),original.categoryTrends.keys)
        assertEquals(setOf(ChartCategory.CREDIT_PURCHASES),BetaChartDataWorkflow(repo).categories(original,key))
        assertThrows(IllegalArgumentException::class.java) {workflow.save(key,emptySet(),original.categoryTrends.keys)}
        assertEquals(setOf(ChartCategory.CREDIT_PURCHASES),workflow.categories(original,key))
        workflow.save(key,original.categoryTrends.keys,original.categoryTrends.keys)
        assertTrue(repo.load().selections.isEmpty())
        val failing=BetaChartDataWorkflow(object: BetaChartDataPreferencesRepository {
            override fun load()=BetaChartDataPreferences()
            override fun save(value: BetaChartDataPreferences) {error("Synthetic disk failure")}
        })
        assertThrows(IllegalStateException::class.java) {failing.save(key,setOf(ChartCategory.CREDIT_PURCHASES),original.categoryTrends.keys)}
        assertTrue(failing.preferences.value.selections.isEmpty())
        assertEquals(amount("160"),original.totalSpending)
    }
    @Test fun savedIdentityIncludesBankAndWholeAssetNotSuffixOrDisplayName() {
        val a=AssetId(Environment.BETA,BankId("BANK_ALJAZIRA"),"card:7271")
        val b=a.copy(bank=BankId("SECOND_BANK"))
        val c=a.copy(localId="other-card:7271")
        assertEquals(3,listOf(a,b,c).map {BetaChartDataWorkflow.key(TrendScope.ASSET,it)}.distinct().size)
    }
    @Test fun sparseCategoryFillsOnlyProvedEmptyDatesAndUsesFullElapsedMean() {
        val original=trend()
        val sparse=original.categoryTrends.getValue(ChartCategory.CREDIT_PURCHASES).let {it.copy(
            points=it.points.take(1),totalSpending=amount("100"))}
        val changed=original.copy(categoryTrends=original.categoryTrends+(ChartCategory.CREDIT_PURCHASES to sparse))
        val selected=BetaChartDataWorkflow.select(changed,setOf(ChartCategory.CREDIT_PURCHASES))
        assertEquals(listOf("100.00","0.00"),selected.points.map {it.net!!.amount.toPlainString()})
        assertEquals(amount("50.00"),selected.averageDailySpending)
        assertTrue(selected.points.last().transactions.isEmpty())
        val unavailable=changed.copy(categoryTrends=changed.categoryTrends+(ChartCategory.CREDIT_PURCHASES to sparse.copy(totalSpending=null)))
        assertNull(BetaChartDataWorkflow.select(unavailable,setOf(ChartCategory.CREDIT_PURCHASES)).points.last().net)
    }

}
