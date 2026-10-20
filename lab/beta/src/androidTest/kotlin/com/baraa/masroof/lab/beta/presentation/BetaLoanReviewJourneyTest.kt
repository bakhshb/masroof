package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BetaLoanReviewJourneyTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun await(tag: String) {
        try {compose.waitUntil(20000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()};compose.waitForIdle()}
        catch(error: Throwable) {
            println("Loan review waiting for $tag")
            runCatching {compose.onRoot().printToLog("BetaLoanReview")}
            val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /sdcard/beta-loan-review-failure.png")).use {it.readBytes()}
            throw error
        }
    }
    private fun click(tag: String) {
        await(tag)
        compose.waitUntil(20000) {runCatching {compose.onNodeWithTag(tag).assertIsEnabled();true}.getOrDefault(false)}
        val node=compose.onNodeWithTag(tag)
        if(tag in setOf("asset-save","asset-cancel","review-asset-apply","review-asset-cancel")) node.assertIsDisplayed().performClick()
        else node.performScrollTo().assertIsDisplayed().performClick()
    }
    @Test fun loanReviewConfiguresALoanInPlaceAndBackReturnsToMessage() {
        BetaContainer.resetOffline(context)
        val bank=BankId("BANK_ALJAZIRA")
        BetaContainer(context).use {beta ->
            beta.acknowledgeStart();beta.setBankEnabled(bank,true)
            beta.registerAsset(OwnedAsset(AssetId(Environment.BETA,bank,"loan-source"),AssetKind.ACCOUNT,"3001"))
            beta.importEvidence(EvidenceRequest("loan-review",EvidenceOrigin.EXPLICIT_HISTORY,setOf(bank)),listOf(EvidenceMessage("loan","AlJazira",
                "خصم: قسط تمويل\nمن: 3001\nالقسط: SAR 20\nلـ: تمويل\nفي: 2026-10-18 01:10",Instant.now(),ReceiptTimeSource.PROVIDER_TIMESTAMP)))
            beta.processAutomaticEvidence()
            assertEquals("CONFIRM_LOAN",beta.reviewInboxPage().rows.single().reason)
        }
        try {ActivityScenario.launch(BetaActivity::class.java).use {scenario ->
            await("dashboard-ready")
            compose.onNode(hasText(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_review)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
            click("history-review-0");click("review-configure-loan")
            compose.onNodeWithTag("asset-kind-LOAN").assertExists()
            compose.onNodeWithTag("asset-kind-CREDIT_CARD").assertDoesNotExist()
            compose.onNodeWithTag("asset-kind-DEBIT_CARD").assertDoesNotExist()
            click("asset-loan-type-PERSONAL");click("asset-owned-confirm");click("asset-save")
            // The message remains composed beneath the sheet while the financial save runs.
            // Wait for sheet dismissal and re-enabled controls before testing Back.
            compose.waitUntil(20000) {compose.onAllNodesWithTag("asset-save").fetchSemanticsNodes().isEmpty()}
            await("review-configure-loan")
            compose.waitUntil(20000) {runCatching {compose.onNodeWithTag("review-configure-loan").assertIsEnabled();true}.getOrDefault(false)}
            compose.onNodeWithTag("manage-CARDS").assertDoesNotExist()
            compose.onNodeWithTag("review-resolve-LOAN_REPAYMENT").assertExists()
            scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
            await("history-review-0")
            click("history-review-0");click("review-resolve-LOAN_REPAYMENT");click("review-select-destination")
            val loanId=BetaContainer(context).use {it.assets().single {asset -> asset.kind==AssetKind.LOAN}.id}
            click("review-endpoint-${com.baraa.masroof.lab.beta.application.evidence.betaAssetChoiceKey(loanId)}")
            click("review-asset-apply");click("review-confirm-resolution");await("review-empty")
        }
            val done=CountDownLatch(1);BetaIo.execute {done.countDown()};assertTrue(done.await(20,TimeUnit.SECONDS))
            BetaContainer(context).use {beta ->assertEquals(1,beta.assets().count {it.kind==AssetKind.LOAN})}
        } finally {
            val closed=CountDownLatch(1);BetaIo.execute {closed.countDown()};assertTrue(closed.await(20,TimeUnit.SECONDS))
            BetaContainer.resetOffline(context)
        }
    }
}
