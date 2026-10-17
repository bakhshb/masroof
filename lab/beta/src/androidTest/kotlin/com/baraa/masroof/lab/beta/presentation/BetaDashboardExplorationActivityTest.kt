package com.baraa.masroof.lab.beta.presentation

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.dashboard.TrendScope
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BetaDashboardExplorationActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val key = PostingKey(Environment.BETA, "synthetic-full-activity")
    private fun waitLoaded() { compose.waitUntil(20000) { compose.onAllNodesWithTag("dash-refresh").fetchSemanticsNodes().isNotEmpty() } }
    private fun route(name: String, scenario: ActivityScenario<BetaActivity>) {
        val tag = "dash-route-$name"
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false }
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("dash-refresh").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("dash-loading").fetchSemanticsNodes().isEmpty()
            }
            compose.waitForIdle()
            // A dismissed dialog may leave its Android window focused after its nodes disappear.
            compose.waitUntil(15000) { var focused = false; scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }; focused }
            compose.waitUntil(15000) {
                // The route strip scrolls horizontally inside the vertically scrolling shell.
                // Bring its row into the viewport before scrolling to a particular chip.
                runCatching { compose.onNodeWithTag("dash-routes").performScrollTo(); compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled(); true }.getOrDefault(false)
            }
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.Selected) == true }
            if (name == "CONFIGURATION") compose.waitUntil(15000) { compose.onAllNodesWithTag("dash-configuration").fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            runCatching { println(compose.onRoot(useUnmergedTree = true).printToString()) }
            runCatching { screenshot("activity-route-failed-$name", false) }
            throw failure
        }
    }
    private fun closed() { val latch = CountDownLatch(1); BetaIo.execute { latch.countDown() }; assertTrue(latch.await(15, TimeUnit.SECONDS)) }
    private fun screenshot(name: String, assertChrome: Boolean = true) {
        compose.waitForIdle()
        if (assertChrome) { compose.onNodeWithTag("beta-badge").assertIsDisplayed(); compose.onNodeWithTag("beta-return").assertIsDisplayed() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun command(value: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(value)).use { it.readBytes() }
        command("mkdir -p /sdcard/beta-dashboard-screenshots")
        command("screencap -p /sdcard/beta-dashboard-screenshots/$name.png")
        val png = command("cat /sdcard/beta-dashboard-screenshots/$name.png")
        assertTrue(png.size > 8); assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), png.copyOfRange(0, 8))
    }
    @Test @SdkSuppress(minSdkVersion = 33)
    fun actualRtlActivityDrilldownAndChartPreferencesSurviveRecreationAndFreshLaunchWithoutPosting() = runBlocking {
        BetaContainer.resetOffline(context)
        val bank = BankId("BANK_ALJAZIRA")
        val account = OwnedAsset(AssetId(Environment.BETA, bank, "synthetic-full-account"), AssetKind.ACCOUNT, "4567")
        val postedAt = Instant.now().minusSeconds(60)
        BetaContainer(context).use { beta ->
            beta.setBankEnabled(bank, true)
            beta.saveManualAsset(ManualAssetDraft(account.id, account.kind, checkNotNull(account.last4), ownedConfirmed = true))
        }
        val seeded = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        try {
            assertTrue(RoomBetaFinancialLedger(seeded).post(FinancialClassificationInput(key, PostingRevision(0, 0), FinancialType.EXPENSE,
                Money.of("42.35", Currency.SAR), TransactionTime(postedAt, ZoneId.of("Asia/Riyadh")),
                FinancialEndpoint.Owned(account.id), FinancialEndpoint.External, mapOf(account.id to account), setOf("synthetic-full-evidence"), true)) is FinancialPostingResult.Applied)
        } finally { seeded.close() }
        val locales = context.getSystemService(LocaleManager::class.java)
        val original = locales.applicationLocales
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { locales.applicationLocales = LocaleList.forLanguageTags("ar") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
            compose.waitUntil(15000) { compose.onAllNodesWithTag("beta-start-continue").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("beta-start-continue").performClick()
                waitLoaded(); compose.onNodeWithText("نظرة عامة").assertExists(); screenshot("activity-rtl-overview")
                scenario.onActivity { activity ->
                    assertEquals(View.LAYOUT_DIRECTION_RTL, activity.window.decorView.layoutDirection)
                    assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
                    assertEquals(1f, activity.resources.configuration.fontScale, .001f)
                    println("BetaActivity visual metrics: api=${android.os.Build.VERSION.SDK_INT}, fontScale=${activity.resources.configuration.fontScale}, screenDp=${activity.resources.configuration.screenWidthDp}x${activity.resources.configuration.screenHeightDp}, densityDpi=${activity.resources.configuration.densityDpi}, direction=RTL")
                }
                route("TRANSACTIONS", scenario)
                compose.onNodeWithTag("dash-transaction-${key.transactionId}").performScrollTo().performClick()
                compose.onNodeWithTag("transaction-detail").assertExists(); compose.onNodeWithText("42.35 SAR").assertExists()
                screenshot("activity-rtl-transaction-detail")
                scenario.recreate(); waitLoaded(); compose.onNodeWithTag("transaction-detail").assertExists()
                compose.onNodeWithTag("dash-detail-back").performScrollTo().performClick()
                route("ANALYSIS", scenario)
                val day = postedAt.atZone(ZoneId.of("Asia/Riyadh")).toLocalDate()
                compose.onNodeWithTag("chart-day-$day").performScrollTo()
                screenshot("activity-rtl-chart")
                compose.onNodeWithTag("chart-day-$day").performClick()
                compose.onNodeWithTag("dash-transaction-${key.transactionId}").performScrollTo().performClick()
                compose.onNodeWithTag("transaction-detail").assertExists()
                compose.onNodeWithTag("dash-detail-back").performScrollTo().performClick()
                compose.onNodeWithTag("dash-customize").performScrollTo().performClick()
                compose.onNodeWithTag("chart-scope-FINANCIAL_OUTFLOWS").performScrollTo().performClick()
                compose.onNodeWithTag("chart-category-FEES").performScrollTo().performClick()
                compose.onNodeWithTag("chart-table").performScrollTo().performClick()
                compose.onNodeWithTag("dash-sheet-cancel").performClick()
                compose.waitUntil { compose.onAllNodesWithTag("dash-sheet").fetchSemanticsNodes().isEmpty() }
                assertEquals(BetaDashboardViewPreferences(), BetaDashboardViewPreferencesWorkflow(context).read())
                compose.onNodeWithTag("dash-customize").performScrollTo().performClick()
                compose.onNodeWithTag("chart-scope-FINANCIAL_OUTFLOWS").performScrollTo().performClick()
                compose.onNodeWithTag("chart-category-FEES").performScrollTo().performClick()
                compose.onNodeWithTag("chart-table").performScrollTo().performClick()
                compose.onNodeWithTag("dash-sheet-apply").performClick()
                compose.waitUntil { compose.onAllNodesWithTag("dash-sheet").fetchSemanticsNodes().isEmpty() }
                val chosen = BetaDashboardViewPreferencesWorkflow(context).read()
                assertEquals(TrendScope.FINANCIAL_OUTFLOWS, chosen.choice.scope); assertFalse(chosen.bars)
                route("OVERVIEW", scenario); compose.onNodeWithText("42.35 SAR").assertExists()
            }
            closed()
            ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
                waitLoaded(); route("ANALYSIS", scenario); compose.onNodeWithTag("dash-customize").performScrollTo().performClick()
                compose.onNodeWithTag("chart-scope-FINANCIAL_OUTFLOWS").assertIsSelected()
                compose.onNodeWithTag("chart-category-FEES").performScrollTo().assertIsSelected()
                compose.onNodeWithTag("chart-table").performScrollTo().assertIsSelected()
                compose.onNodeWithTag("dash-sheet-cancel").performClick()
                compose.waitUntil { compose.onAllNodesWithTag("dash-sheet").fetchSemanticsNodes().isEmpty() }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                route("CONFIGURATION", scenario); compose.onNodeWithTag("dash-configuration").assertExists()
                screenshot("activity-rtl-configuration")
                scenario.recreate(); waitLoaded(); compose.onNodeWithTag("dash-route-CONFIGURATION").assertIsSelected()
            }
            closed()
            val verified = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
            try { assertEquals(1, RoomBetaFinancialLedger(verified).history(key).size) } finally { verified.close() }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { locales.applicationLocales = original }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            closed(); BetaContainer.resetOffline(context)
        }
    }
}
