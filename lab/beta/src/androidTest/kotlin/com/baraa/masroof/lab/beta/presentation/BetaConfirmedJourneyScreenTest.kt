package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.value.BankId
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BetaConfirmedJourneyScreenTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun label(id: Int): String = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {setLocale(java.util.Locale("ar"))}).getString(id)
    private fun await(tag: String) {
        compose.waitUntil(20000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Let the committed Compose frame reach the emulator display before screencap.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {it.readBytes()}
        shell("mkdir -p /sdcard/beta-confirmed-journey")
        shell("screencap -p /sdcard/beta-confirmed-journey/$name.png")
        assertTrue(shell("cat /sdcard/beta-confirmed-journey/$name.png").size>8)
    }
    @Test fun pendingMessageKeepsConfirmedHomeAccountsCardsAndTransactionsVisible() {
        BetaContainer.resetOffline(context)
        val at=Instant.now()
        val date=at.atZone(ZoneId.of("Asia/Riyadh")).toLocalDateTime().withSecond(0).withNano(0)
        BetaContainer(context).use { beta ->
            beta.acknowledgeStart();beta.setBankEnabled(BankId("BANK_ALJAZIRA"),true);beta.setLanguage("ar")
            beta.importEvidence(EvidenceRequest("screen-confirmed",EvidenceOrigin.EXPLICIT_SAMPLE),listOf(
                EvidenceMessage("purchase","AlJazira","شراء عبر نقاط البيع\nبطاقة مدى: 5678\nلدى: TEST SHOP\nبمبلغ: SAR 25\nخصمت من حساب: 1234\nفي: $date",at,ReceiptTimeSource.USER_SUPPLIED),
                EvidenceMessage("loan","AlJazira","خصم: قسط تمويل\nمن: 1234\nالقسط: SAR 125\nالمبلغ المتبقي: SAR 1,000\nلـ: تمويل شخصي\nفي: $date",at.plusSeconds(1),ReceiptTimeSource.USER_SUPPLIED),
                EvidenceMessage("unknown","AlJazira","Unknown banking layout",at,ReceiptTimeSource.USER_SUPPLIED)))
            beta.ownershipSnapshot().candidates.forEach {beta.decideOwnership(it,true)}
            beta.processAutomaticEvidence()
        }
        try {
            ActivityScenario.launch(BetaActivity::class.java).use {scenario ->
                await("dashboard-ready");await("beta-home-hero")
                compose.onNodeWithTag("dash-summary-spending").performScrollTo().assertIsDisplayed();screenshot("home")
                compose.onNodeWithTag("home-shortcuts").performScrollTo()
                compose.onNodeWithTag("dashboard-shortcut-0").performClick()
                await("dash-route-ACCOUNTS")
                screenshot("accounts")
                scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                await("home-shortcuts");compose.onNodeWithTag("home-shortcuts").performScrollTo()
                compose.onNodeWithTag("dashboard-shortcut-1").performClick();await("dash-route-CARDS");screenshot("cards")
                scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                await("home-shortcuts");compose.onNodeWithTag("home-shortcuts").performScrollTo()
                compose.onNodeWithTag("dashboard-shortcut-2").performClick();await("dash-route-LOANS");screenshot("loans")
                scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                await("beta-bottom-navigation")
                compose.onNode(hasText(label(R.string.beta_nav_transactions)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
                await("transactions-search");await("transactions-share");screenshot("transactions")
                compose.onNodeWithTag("transactions-search").performTextReplacement("TEST SHOP")
                compose.onNode(hasText("TEST SHOP") and hasAnyAncestor(hasTestTag("transactions-list"))).assertIsDisplayed().performClick()
                await("transaction-detail")
                compose.onNodeWithTag("beta-page-back").performClick()
                await("transactions-search")
                compose.onNodeWithTag("transactions-search").assertTextContains("TEST SHOP")
                compose.onNodeWithTag("transactions-search").performTextClearance()
                compose.onNode(hasText(label(R.string.beta_nav_review)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
                await("review-item-0");screenshot("review")
                compose.onAllNodesWithTag("review-item-1").assertCountEquals(0)
            }
            val drained=CountDownLatch(1);BetaIo.execute {drained.countDown()};assertTrue(drained.await(20,TimeUnit.SECONDS))
            BetaContainer(context).use {beta ->assertEquals(3,beta.assets().size);assertEquals(1,beta.reviewInboxPage().rows.size)}
        } finally {BetaContainer.resetOffline(context)}
    }
}
