package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.BetaIo
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BetaDashboardActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun actualBetaActivityPreservesDashboardDetailAcrossRecreationAndUsesTheSharedBackButton() {
        BetaContainer.resetOffline(context)
        BetaContainer(context).use { beta ->
            val bank = BankId("BANK_ALJAZIRA")
            beta.setBankEnabled(bank, true)
            beta.saveManualAsset(com.baraa.masroof.lab.beta.application.ManualAssetDraft(AssetId(Environment.BETA, bank, "synthetic-activity"), AssetKind.ACCOUNT, "1234", ownedConfirmed = true))
        }
        ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
            compose.waitUntil(15000) { compose.onAllNodesWithTag("beta-start-continue").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("beta-start-continue").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("dashboard-ready").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("beta-badge").assertIsDisplayed()
            compose.onNodeWithTag("beta-bottom-navigation").assertIsDisplayed()
            compose.onNodeWithTag("home-shortcuts").performScrollTo()
            compose.onNodeWithTag("dashboard-shortcut-0").performClick()
            compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-synthetic-activity").performScrollTo().performClick()
            compose.onNodeWithTag("beta-page-back").assertExists()
            compose.onNodeWithTag("beta-notifications").assertIsDisplayed()
            compose.onNode(hasContentDescription(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_home)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).assertIsNotSelected()
            scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("dashboard-ready").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("beta-page-back").assertExists()
            compose.onNodeWithTag("beta-badge").assertIsDisplayed()
            compose.onNodeWithTag("beta-bottom-navigation").assertIsDisplayed()
            compose.onNodeWithTag("beta-page-back").performClick()
            compose.onNodeWithTag("dash-route-ACCOUNTS").assertIsSelected()
        }
        val closed = CountDownLatch(1); BetaIo.execute { closed.countDown() }; assertTrue(closed.await(15, TimeUnit.SECONDS))
        BetaContainer(context).use { beta -> assertEquals(1, beta.assets().size); assertEquals(0, beta.markerCount()) }
        BetaContainer.resetOffline(context)
    }
}

