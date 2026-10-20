package com.baraa.masroof.lab.beta.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.baraa.masroof.lab.beta.application.BetaDashboardWorkflow
import com.baraa.masroof.lab.finance.dashboard.producer.DashboardProduction
import com.baraa.masroof.ui.components.MasroofMoneyDisplay
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.*

class BetaDailyPagesTest {
    @get:Rule val compose=createComposeRule()
    @Test fun merchantAndDailyJourneysUseProvenanceAndRestoreCardDateAfterTransactionDetail() {
        val zone=ZoneId.of("Asia/Riyadh")
        val workflow=BetaDashboardWorkflow({query,_ -> DashboardProduction(BetaFrozenProductionRead.snapshot(query),emptySet(),null)},
            Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"),zone),zone)
        val restoration=StateRestorationTester(compose)
        var refund=""
        restoration.setContent {BetaLocaleProvider("en") {MasroofTheme {
            refund=MasroofMoneyDisplay.format(BigDecimal("-150"),"SAR",LocalConfiguration.current.locales[0].toLanguageTag())
            Column(Modifier.verticalScroll(rememberScrollState())) {BetaDashboardPanel(workflow,requestedRoute="MERCHANTS")}
        }}}
        fun loaded()=compose.waitUntil(15000) {compose.onAllNodesWithTag("dashboard-ready").fetchSemanticsNodes().isNotEmpty()}
        fun click(tag: String)=compose.onNodeWithTag(tag).performScrollTo().performClick()
        loaded();click("merchant-noon")
        compose.onNodeWithTag("dash-transaction-ref-10").assertExists()
        compose.onNodeWithTag("dash-transaction-ref-11").assertExists()
        compose.onNodeWithTag("dash-transaction-ref-5").assertDoesNotExist()
        click("dash-transaction-ref-11");compose.onNodeWithTag("transaction-detail").assertExists()
        click("dash-detail-back");compose.onNodeWithTag("dash-transaction-ref-10").assertExists()
        click("dash-detail-back");click("merchants-daily")
        click("daily-date");click("daily-date-12") // 9 October from the frozen 14-day source window.
        click("daily-scope");click("daily-scope-3") // Primary, supplementary, then Mada, as in production.
        compose.onNode(hasText(refund) and hasAnyAncestor(hasTestTag("daily-net"))).assertExists()
        compose.onNodeWithTag("dash-transaction-ref-7").assertExists()
        compose.onNodeWithTag("dash-transaction-ref-13").assertDoesNotExist()
        click("dash-transaction-ref-7");compose.onNodeWithTag("transaction-detail").assertExists()
        restoration.emulateSavedInstanceStateRestore();loaded()
        compose.onNodeWithTag("transaction-detail").assertExists()
        click("dash-detail-back")
        compose.onNode(hasText(refund) and hasAnyAncestor(hasTestTag("daily-net"))).assertExists()
        compose.onNodeWithTag("dash-transaction-ref-7").assertExists()
        compose.onNodeWithTag("dash-transaction-ref-13").assertDoesNotExist()
        click("dash-overview");compose.onNodeWithTag("merchant-noon").assertExists()
    }
}
