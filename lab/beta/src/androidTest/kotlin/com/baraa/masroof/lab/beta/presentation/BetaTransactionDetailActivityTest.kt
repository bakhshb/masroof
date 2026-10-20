package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.corrections.*
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.corrections.FinancialDecision
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BetaTransactionDetailActivityTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val key=PostingKey(Environment.BETA,"detail-activity")
    private fun await(tag: String)=compose.waitUntil(20000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
    private fun click(tag: String) {
        await(tag)
        val node=compose.onNodeWithTag(tag)
        if(tag in setOf("transaction-type-confirm","transaction-type-confirm-cancel","transaction-ignore-confirm","transaction-ignore-cancel","transactions-share","transaction-type-save","transaction-type-cancel")) node.assertIsDisplayed().performClick()
        else node.performScrollTo().performClick()
    }
    private fun resetAfterActivity() {
        val closed=CountDownLatch(1);BetaIo.execute {closed.countDown()}
        assertTrue(closed.await(15,TimeUnit.SECONDS));BetaContainer.resetOffline(context)
    }
    private fun database()=Room.databaseBuilder(context,BetaDatabase::class.java,BetaDatabase.NAME).build()
    private suspend fun seed() {
        BetaContainer.resetOffline(context)
        val bank=BankId("BANK_ALJAZIRA")
        val account=OwnedAsset(AssetId(Environment.BETA,bank,"detail-account"),AssetKind.ACCOUNT,"3001")
        BetaContainer(context).use {beta ->
            beta.setLanguage("en");beta.setBankEnabled(bank,true)
            beta.saveManualAsset(ManualAssetDraft(account.id,account.kind,"3001",ownedConfirmed=true))
        }
        val db=database()
        try {
            val input=FinancialClassificationInput(key,PostingRevision(0,1),FinancialType.EXPENSE,Money.of("42.35",Currency.SAR),
                TransactionTime(Instant.now().minusSeconds(60),ZoneId.of("Asia/Riyadh")),FinancialEndpoint.Owned(account.id),FinancialEndpoint.External,
                mapOf(account.id to account),setOf("detail-evidence"),true)
            assertTrue(RoomBetaFinancialLedger(db).post(input) is FinancialPostingResult.Applied)
            val review=BetaReviewWorkflow(db)
            assertTrue(review.save(review.detail(key)!!.token,"name",FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE),mapOf("merchant" to "Detail merchant")) is BetaCorrectionResult.Applied)
        } finally {db.close()}
    }
    private fun open() {
        click("beta-start-continue");await("dashboard-ready")
        compose.onNode(hasContentDescription("Transactions") and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
        click("dash-transaction-${key.transactionId}");await("transaction-change-type")
    }
    private fun chooseFee() {
        click("transaction-change-type")
        compose.onNodeWithTag("transaction-type-save").assertIsNotEnabled()
        compose.onNodeWithTag("transaction-type-EXPENSE").assertIsNotEnabled()
        click("transaction-type-FEE");click("transaction-type-save");await("transaction-type-confirm")
    }
    @Test fun actualDetailShareAndTwoStepReclassificationCancelThenSaveAndIgnore()=runBlocking {
        seed()
        val db=database()
        var chooser: Intent?=null
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val monitor=object: android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if(intent.action!=Intent.ACTION_CHOOSER) return null
                chooser=intent;return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val ledger=RoomBetaFinancialLedger(db);val before=ledger.history(key)
            ActivityScenario.launch(BetaActivity::class.java).use {scenario ->
                open()
                compose.onNodeWithText("Transaction info").assertExists()
                compose.onNodeWithText("Direction").assertExists()
                compose.onNodeWithText("Outgoing").assertExists()
                click("transactions-share")
                val send=chooser?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                assertEquals(Intent.ACTION_SEND,send?.action)
                val text=send!!.getStringExtra(Intent.EXTRA_TEXT)!!
                assertTrue(text.contains("Detail merchant"));assertTrue(text.contains("42.35"));assertTrue(text.contains("Direction: Outgoing"))
                assertFalse(text.contains("Count:"))
                chooseFee();assertEquals(before,ledger.history(key))
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                await("transaction-type-confirm");assertEquals(before,ledger.history(key))
                click("transaction-type-confirm-cancel");click("transaction-type-cancel")
                assertEquals(before,ledger.history(key))
                chooseFee();scenario.recreate();await("transaction-type-confirm")
                compose.waitUntil(15000) {runCatching {compose.onNodeWithTag("transaction-type-confirm").assertIsEnabled();true}.getOrDefault(false)}
                click("transaction-type-confirm");await("transaction-reclassified")
                assertEquals(FinancialType.FEE,ledger.readAudit(key)!!.classification.effectiveType)
                val changed=ledger.history(key)
                click("transaction-ignore");click("transaction-ignore-cancel");assertEquals(changed,ledger.history(key))
                click("transaction-ignore");click("transaction-ignore-confirm");await("dashboard-ready")
                compose.waitUntil(15000) {
                    compose.onAllNodesWithTag("dashboard-ready").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithTag("dash-transaction-${key.transactionId}").fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodesWithTag("transaction-detail").fetchSemanticsNodes().isEmpty()
                }
                assertEquals(BetaLedgerActivity.SUPPRESSED,BetaReviewWorkflow(db).detail(key)!!.current!!.activity)
            }
        } finally {instrumentation.removeMonitor(monitor);db.close();resetAfterActivity()}
    }
    @Test fun restoredDraftAndIgnoreCannotSilentlyAdoptANewerFinancialToken()=runBlocking {
        seed();val db=database()
        try {
            val ledger=RoomBetaFinancialLedger(db);val review=BetaReviewWorkflow(db)
            ActivityScenario.launch(BetaActivity::class.java).use {scenario ->
                open();chooseFee()
                assertTrue(review.save(review.detail(key)!!.token,"concurrent-name",FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE),mapOf("merchant" to "Changed elsewhere")) is BetaCorrectionResult.Applied)
                val newer=ledger.history(key)
                scenario.recreate();await("transaction-type-confirm");click("transaction-type-confirm");await("transaction-action-error")
                assertEquals(newer,ledger.history(key));assertEquals(FinancialType.EXPENSE,ledger.readAudit(key)!!.classification.effectiveType)
                click("transaction-ignore")
                assertTrue(review.save(review.detail(key)!!.token,"concurrent-name-2",FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE),mapOf("merchant" to "Changed again")) is BetaCorrectionResult.Applied)
                val latest=ledger.history(key)
                scenario.recreate();await("transaction-ignore-confirm")
                compose.waitUntil(15000) {runCatching {compose.onNodeWithTag("transaction-ignore-confirm").assertIsEnabled();true}.getOrDefault(false)}
                click("transaction-ignore-confirm");await("transaction-action-error")
                assertEquals(latest,ledger.history(key));assertEquals(BetaLedgerActivity.ACTIVE,review.detail(key)!!.current!!.activity)
            }
        } finally {db.close();resetAfterActivity()}
    }
}
