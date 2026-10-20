package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaEnvironment
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.DashboardProduction
import com.baraa.masroof.lab.finance.posting.PostingKey
import com.baraa.masroof.lab.finance.value.*
import com.baraa.masroof.ui.components.MasroofMoneyDisplay
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.*

class BetaChartCustomizationTest {
    @get:Rule val compose=createComposeRule()
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun signed(value: String)=SignedAmount(BigDecimal(value),Currency.SAR)
    private fun snapshot(query: DashboardQuery): DashboardSnapshot {
        val period=DisplayPeriod(query.from,query.until,query.zone,PeriodKind.SALARY,null)
        val date=query.from.atZone(query.zone).toLocalDate()
        fun part(category: ChartCategory,value: String)=SpendingTrend(null,category,period,Currency.SAR,
            listOf(DailySpending(date,signed(value),signed(value),signed("0"),setOf(PostingKey(Environment.BETA,category.name)))),
            signed(value),scope=TrendScope.CREDIT_CARDS,totalSpending=signed(value),totalBeforeRefunds=signed(value),totalRefunds=signed("0"))
        val purchase=part(ChartCategory.CREDIT_PURCHASES,"100")
        val fee=part(ChartCategory.FEES,"2")
        val trend=SpendingTrend(null,null,period,Currency.SAR,
            listOf(DailySpending(date,signed("102"),signed("102"),signed("0"),purchase.points[0].transactions+fee.points[0].transactions)),
            signed("102"),TrendScope.CREDIT_CARDS,signed("102"),signed("102"),signed("0"),mapOf(ChartCategory.CREDIT_PURCHASES to purchase,ChartCategory.FEES to fee))
        val id=AssetId(Environment.BETA,BankId("BANK_ALJAZIRA"),"primary")
        val card=CreditCardSummary(id,CreditCardRole.PRIMARY,"Primary","7271",null,PeriodSpending(period,signed("102")),null,null,null)
        val facility=FacilitySummary(id.copy(localId="facility"),Money.of("2200",Currency.SAR),null,null,cards=listOf(card))
        return DashboardSnapshot(query,emptyList(),emptyList(),listOf(facility),
            analysis=AnalysisSummary(Currency.SAR,SummaryMetric.entries.associateWith {null},2,0,0,listOf(trend),emptyList()),
            totals=PortfolioTotals(Currency.SAR,null,null,null,null,Money.of("2200",Currency.SAR),signed("102"),null,null,null))
    }
    @Test fun actualDashboardChartUsesDraftSaveResetAndDurableCategoriesWithoutChangingDue() {
        val prefs=context.getSharedPreferences(BetaEnvironment.PREFS_NAME,Context.MODE_PRIVATE)
        prefs.edit().remove("dashboard_chart_categories").commit()
        try {
            val preferences=BetaDashboardViewPreferencesWorkflow(context)
            val workflow=BetaDashboardWorkflow({query,_ -> DashboardProduction(snapshot(query),emptySet(),null)},
                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"),ZoneOffset.UTC),ZoneOffset.UTC)
            val restoration=StateRestorationTester(compose)
            var due=""
            restoration.setContent {BetaLocaleProvider("en") {MasroofTheme {
                due=MasroofMoneyDisplay.format(BigDecimal("2200"),"SAR",LocalConfiguration.current.locales[0].toLanguageTag())
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BetaDashboardPanel(workflow,preferences=preferences,requestedRoute="CARDS")
                }
            }}}
            compose.waitUntil(15000) {compose.onAllNodesWithTag("dashboard-ready").fetchSemanticsNodes().isNotEmpty()}
            fun click(tag: String) {
                val node=compose.onNodeWithTag(tag)
                if(tag.startsWith("chart-data-")) node.performClick() else node.performScrollTo().performClick()
            }
            fun dueUnchanged()=compose.onNode(hasText(due) and hasAnyAncestor(hasTestTag("cards-facility-due-total"))).assertExists()
            click("chart-customize");click("chart-category-FEES")
            click("chart-data-cancel")
            assertTrue(preferences.chartData.preferences.value.selections.isEmpty())
            compose.onAllNodesWithTag("chart-custom-summary").assertCountEquals(0)
            click("chart-customize");click("chart-category-FEES");click("chart-data-apply")
            compose.onNodeWithTag("chart-custom-summary").assertExists();dueUnchanged()
            val key=BetaChartDataWorkflow.key(TrendScope.CREDIT_CARDS,null)
            assertEquals(listOf(ChartCategory.CREDIT_PURCHASES),BetaDashboardViewPreferencesWorkflow(context).chartData.preferences.value.selections[key])
            assertTrue(prefs.all.getValue("dashboard_chart_categories") is String)
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("dashboard-ready").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("chart-custom-summary").assertExists();dueUnchanged()
            click("chart-customize");click("chart-category-CREDIT_PURCHASES")
            compose.onNodeWithTag("chart-data-apply").assertIsNotEnabled()
            click("chart-data-cancel")
            assertEquals(listOf(ChartCategory.CREDIT_PURCHASES),preferences.chartData.preferences.value.selections[key])
            click("chart-customize");click("chart-category-FEES");click("chart-data-apply")
            compose.onAllNodesWithTag("chart-custom-summary").assertCountEquals(0)
            assertTrue(BetaDashboardViewPreferencesWorkflow(context).chartData.preferences.value.selections.isEmpty());dueUnchanged()
        } finally {prefs.edit().remove("dashboard_chart_categories").commit()}
    }
}
