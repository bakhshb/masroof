package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.R
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BetaSharedNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun sharedTabsPreserveNewBetaDataAndExistingSelection() {
        Assume.assumeTrue(context.packageName == "com.baraa.masroof.lab.beta.test" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        BetaContainer.resetOffline(context)
        try {
            BetaContainer(context).use { it.putMarker("m2-new-data", "keep"); it.acknowledgeStart() }
            ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
                await("dash-refresh"); await("beta-bottom-navigation")
                tab(R.string.beta_nav_transactions).performClick()
                compose.onNodeWithTag("dash-route-TRANSACTIONS").assertIsSelected()
                scenario.recreate(); await("dash-refresh")
                compose.onNodeWithTag("dash-route-TRANSACTIONS").assertIsSelected()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("dash-route-OVERVIEW").assertIsSelected()
                tab(R.string.beta_nav_review).performClick(); await("review-close")
                compose.onNodeWithTag("review-close").performScrollTo().performClick(); await("beta-bottom-navigation")
                tab(R.string.beta_nav_settings).performClick(); await("beta-settings-panel")
                compose.onNodeWithTag("beta-settings-close").performScrollTo().performClick(); await("beta-bottom-navigation")
                compose.onNodeWithTag("beta-badge").assertIsDisplayed()
                compose.onNodeWithTag("beta-return").performClick()
            }
            drain()
            BetaContainer(context).use { assertEquals(1, it.markerCount()); assertEquals(com.baraa.masroof.lab.beta.application.BetaStartState.READY, it.startState()) }
            ActivityScenario.launch(BetaActivity::class.java).use { await("dash-refresh"); compose.onNodeWithTag("beta-start-continue").assertDoesNotExist() }
        } finally { drain(); BetaContainer.resetOffline(context) }
    }
    private fun tab(label: Int) = compose.onNode(hasText(context.getString(label)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation")))
    private fun await(tag: String) { compose.waitUntil(20000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; compose.waitForIdle() }
    private fun drain() { val done = CountDownLatch(1); BetaIo.execute { done.countDown() }; assertTrue(done.await(20, TimeUnit.SECONDS)) }
}
