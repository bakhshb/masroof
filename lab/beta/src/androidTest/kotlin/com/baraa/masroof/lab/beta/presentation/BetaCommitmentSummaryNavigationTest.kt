package com.baraa.masroof.lab.beta.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.baraa.masroof.lab.beta.application.BetaDashboardWorkflow
import com.baraa.masroof.lab.finance.dashboard.producer.DashboardProduction
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Source navigation contract: typed detail, Home on detail Back, user editor callback. */
class BetaCommitmentSummaryNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun start(onOpen: (String)->Unit = {}) {
        val zone=ZoneId.of("Asia/Riyadh")
        val workflow=BetaDashboardWorkflow({query,_ -> DashboardProduction(BetaFrozenProductionRead.snapshot(query),emptySet(),null)},
            Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"),zone),zone)
        compose.setContent {BetaLocaleProvider("en") {MasroofTheme {
            BetaDashboardPanel(workflow,ownsScroll=true,requestedRoute="COMMITMENTS",onOpenCommitment=onOpen)
        }}}
        compose.waitUntil(15000) {compose.onAllNodesWithTag("dashboard-ready").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun creditOpensPrimaryCardAndBackReturnsHome() {
        start()
        compose.onNodeWithTag("commitment-summary-credit").performScrollTo().performClick()
        compose.onNodeWithTag("beta-credit-detail").assertExists()
        compose.onNodeWithTag("dash-detail-back").performScrollTo().performClick()
        compose.onNodeWithTag("home-shortcuts").assertExists()
        compose.onNodeWithTag("commitment-summary-credit").assertDoesNotExist()
    }
    @Test fun loanOpensLoanAndBackReturnsHome() {
        start()
        compose.onNodeWithTag("commitment-summary-loan").performScrollTo().performClick()
        compose.onNodeWithTag("beta-loan-detail").assertExists()
        compose.onNodeWithTag("dash-detail-back").performScrollTo().performClick()
        compose.onNodeWithTag("home-shortcuts").assertExists()
    }
    @Test fun userOccurrenceOpensDefinitionRatherThanAnotherAsset() {
        var opened: String?=null
        start {opened=it}
        compose.onNodeWithTag("commitment-summary-internet").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("internet",opened)}
        compose.onNodeWithTag("beta-credit-detail").assertDoesNotExist()
        compose.onNodeWithTag("beta-loan-detail").assertDoesNotExist()
    }
}
