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
    private fun waitLoaded() { compose.waitUntil(20000) { compose.onAllNodesWithTag("dashboard-ready").fetchSemanticsNodes().isNotEmpty() } }
    private fun route(name: String, scenario: ActivityScenario<BetaActivity>) {
        repeat(3) {
            if(compose.onAllNodesWithTag("beta-home-hero").fetchSemanticsNodes().isEmpty()) {
                scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()
            }
        }
        compose.waitUntil(15000) {compose.onAllNodesWithTag("beta-home-hero").fetchSemanticsNodes().isNotEmpty()}
        when(name) {
            "OVERVIEW" -> Unit
            "TRANSACTIONS" -> compose.onNode(hasText(context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {setLocale(java.util.Locale("ar"))}).getString(com.baraa.masroof.lab.beta.R.string.beta_nav_transactions)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
            "ANALYSIS" -> compose.onNodeWithTag("home-chart-customize").performScrollTo().performClick()
            "CONFIGURATION" -> {
                compose.onNodeWithTag("home-configure").performScrollTo().performClick()
                compose.onNodeWithTag("home-period-settings").performScrollTo().performClick()
            }
            else -> error("Unsupported test destination")
        }
        compose.waitUntil(15000) {compose.onAllNodesWithTag("dash-route-$name").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("dashboard-ready").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("dash-loading").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("dash-route-$name").assertIsSelected()
    }
    private fun waitDetail() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("transaction-detail").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun closed() { val latch = CountDownLatch(1); BetaIo.execute { latch.countDown() }; assertTrue(latch.await(15, TimeUnit.SECONDS)) }
    private fun screenshot(name: String, assertChrome: Boolean = true) {
        compose.waitForIdle()
        if (assertChrome) { compose.onNodeWithTag("beta-badge").assertIsDisplayed(); compose.onNodeWithTag("beta-bottom-navigation").assertIsDisplayed() }
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
                waitLoaded(); compose.onNodeWithTag("beta-home-hero").assertExists(); screenshot("activity-rtl-overview")
                scenario.onActivity { activity ->
                    assertEquals(View.LAYOUT_DIRECTION_RTL, activity.window.decorView.layoutDirection)
                    assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
                    assertEquals(1f, activity.resources.configuration.fontScale, .001f)
                    println("BetaActivity visual metrics: api=${android.os.Build.VERSION.SDK_INT}, fontScale=${activity.resources.configuration.fontScale}, screenDp=${activity.resources.configuration.screenWidthDp}x${activity.resources.configuration.screenHeightDp}, densityDpi=${activity.resources.configuration.densityDpi}, direction=RTL")
                }
                route("TRANSACTIONS", scenario)
                compose.onNodeWithTag("dash-transaction-${key.transactionId}").performScrollTo().performClick()
                waitDetail(); compose.onNodeWithTag("transaction-detail").assertExists(); compose.onNodeWithText(com.baraa.masroof.ui.components.MasroofMoneyDisplay.format(java.math.BigDecimal("42.35"),"SAR","ar")).assertExists()
                screenshot("activity-rtl-transaction-detail")
                scenario.recreate(); waitLoaded(); waitDetail(); compose.onNodeWithTag("transaction-detail").assertExists()
                compose.onNodeWithTag("beta-page-back").performClick()
                route("ANALYSIS", scenario)
                val day = postedAt.atZone(ZoneId.of("Asia/Riyadh")).toLocalDate()
                compose.onNodeWithTag("chart-day-$day").performScrollTo()
                screenshot("activity-rtl-chart")
                compose.onNodeWithTag("chart-day-$day").performClick()
                compose.onNodeWithTag("dash-transaction-${key.transactionId}").performScrollTo().performClick()
                waitDetail(); compose.onNodeWithTag("transaction-detail").assertExists()
                compose.onNodeWithTag("beta-page-back").performClick()
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
                route("OVERVIEW", scenario); compose.onNode(hasText("42.35 ر.س") and hasAnyAncestor(hasTestTag("dash-summary-spending"))).assertExists()
            }
            closed()
            ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
                waitLoaded(); route("ANALYSIS", scenario); compose.onNodeWithTag("dash-customize").performScrollTo().performClick()
                compose.onNodeWithTag("chart-scope-FINANCIAL_OUTFLOWS").assertIsOn()
                compose.onNodeWithTag("chart-category-FEES").performScrollTo().assertIsOn()
                compose.onNodeWithTag("chart-table").performScrollTo().assertIsOn()
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
