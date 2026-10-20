package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BetaReviewParityScreenTest {
    @get:Rule val compose=createEmptyComposeRule()
    private var language="ar"
    private var mode="LIGHT"
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun await(tag: String) {compose.waitUntil(20000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()};compose.waitForIdle()}
    private fun click(tag: String) {await(tag);compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()}
    private fun label(id: Int)=context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {setLocale(java.util.Locale(language))}).getString(id)
    private fun capture(name: String) {
        compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(300)
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun command(text: String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(text)).use {it.readBytes()}
        command("mkdir -p /sdcard/beta-review-parity")
        command("screencap -p /sdcard/beta-review-parity/$name.png")
        assertTrue(command("cat /sdcard/beta-review-parity/$name.png").size>8)
    }
    @Test fun approvedReviewListDetailActionsAndIgnoreRestoreUseOneJourney() = verify("ar","LIGHT")
    @Test fun englishDarkReviewUsesTheSameActionsAndJourney() = verify("en","DARK")
    private fun verify(locale: String,theme: String) {
        language=locale;mode=theme
        BetaContainer.resetOffline(context)
        try {
            val bank=BankId("BANK_ALJAZIRA")
            BetaContainer(context).use {beta ->
                beta.acknowledgeStart();beta.setBankEnabled(bank,true);beta.setLanguage(language);beta.setTheme(mode)
                beta.registerAsset(OwnedAsset(AssetId(Environment.BETA,bank,"parity-account"),AssetKind.ACCOUNT,"3001"))
                beta.importEvidence(EvidenceRequest("parity",EvidenceOrigin.EXPLICIT_HISTORY,setOf(bank)),listOf(EvidenceMessage("loan","AlJazira",
                    "خصم: قسط تمويل\nمن: 3001\nالقسط: SAR 3,036.11\nالمبلغ المتبقي: SAR 30,361.14\nلـ: تمويل\nفي: 00:40 27-09-2026",Instant.parse("2026-09-26T21:41:00Z"),ReceiptTimeSource.PROVIDER_TIMESTAMP)))
                beta.processAutomaticEvidence()
            }
            ActivityScenario.launch(BetaActivity::class.java).use {scenario ->
                await("dash-refresh")
                compose.onNode(hasText(label(com.baraa.masroof.lab.beta.R.string.beta_nav_review)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
                await("review-item-0");capture("list-$language-${mode.lowercase()}")
                click("history-review-0");await("review-configure-loan");capture("detail-$language-${mode.lowercase()}")
                compose.onNodeWithTag("review-advanced-learning").assertDoesNotExist()
                compose.onNodeWithTag("manage-CARDS").assertDoesNotExist()
                val actions=listOf("EXPENSE","INCOME","CREDIT_CARD_PAYMENT","REFUND","CASH_WITHDRAWAL","BILL_PAYMENT","FEE","LOAN_REPAYMENT")
                actions.forEach {compose.onNodeWithTag("review-resolve-$it").assertExists()}
                compose.onNodeWithTag("review-resolve-LOAN_REPAYMENT").performScrollTo();capture("actions-$language-${mode.lowercase()}")
                scenario.recreate();await("review-configure-loan")
                click("review-dismiss");await("review-empty")
                click("review-filter-ignored");click("history-review-0")
                actions.forEach {compose.onNodeWithTag("review-resolve-$it").assertExists()}
                capture("ignored-$language-${mode.lowercase()}");click("review-restore")
                await("review-filter-pending");click("review-filter-pending");await("review-item-0")
            }
            val done=CountDownLatch(1);BetaIo.execute {done.countDown()};assertTrue(done.await(20,TimeUnit.SECONDS))
            BetaContainer(context).use {beta ->assertEquals(1,beta.reviewInboxPage().rows.size);assertTrue(beta.reviewInboxPage(ignored=true).rows.isEmpty())}
        } finally {BetaContainer.resetOffline(context)}
    }
}
