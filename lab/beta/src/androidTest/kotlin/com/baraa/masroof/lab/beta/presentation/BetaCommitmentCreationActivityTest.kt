package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.corrections.*
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.corrections.FinancialDecision
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BetaCommitmentCreationActivityTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun await(tag: String)=compose.waitUntil(20000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
    private fun click(tag: String) {
        await(tag);val node=compose.onNodeWithTag(tag)
        if(tag in setOf("transaction-mark-commitment-confirm","transaction-mark-commitment-cancel","beta-page-back")) node.assertIsDisplayed().performClick()
        else node.performScrollTo().performClick()
    }
    @Test fun realTransactionConfirmationCancelsRestoresAndCreatesOneLinkedDefinitionWithoutPosting()=runBlocking {
        BetaContainer.resetOffline(context)
        val key=PostingKey(Environment.BETA,"creation-activity")
        val bank=BankId("BANK_ALJAZIRA");val zone=ZoneId.of("Asia/Riyadh")
        val account=OwnedAsset(AssetId(Environment.BETA,bank,"creation-account"),AssetKind.ACCOUNT,"3001")
        val at=Instant.now().minusSeconds(60)
        try {
            BetaContainer(context).use {beta ->
                beta.setBankEnabled(bank,true)
                beta.saveManualAsset(ManualAssetDraft(account.id,account.kind,"3001",ownedConfirmed=true))
            }
            val db=Room.databaseBuilder(context,BetaDatabase::class.java,BetaDatabase.NAME).build()
            val before=try {
                val input=FinancialClassificationInput(key,PostingRevision(0,1),FinancialType.EXPENSE,Money.of("42.35",Currency.SAR),
                    TransactionTime(at,zone),FinancialEndpoint.Owned(account.id),FinancialEndpoint.External,mapOf(account.id to account),setOf("creation-evidence"),true)
                assertTrue(RoomBetaFinancialLedger(db).post(input) is FinancialPostingResult.Applied)
                val review=BetaReviewWorkflow(db)
                assertTrue(review.save(review.detail(key)!!.token,"named",FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE),mapOf("merchant" to "Source subscription")) is BetaCorrectionResult.Applied)
                RoomBetaFinancialLedger(db).history(key)
            } finally {db.close()}
            ActivityScenario.launch(BetaActivity::class.java).use {scenario ->
                click("beta-start-continue");await("dashboard-ready")
                compose.onNode(hasContentDescription(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_transactions)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
                click("dash-transaction-${key.transactionId}");await("transaction-mark-commitment")
                click("transaction-mark-commitment");click("transaction-mark-commitment-cancel")
                click("transaction-mark-commitment");scenario.recreate()
                await("transaction-mark-commitment-confirm")
                compose.waitUntil(15000) {runCatching {compose.onNodeWithTag("transaction-mark-commitment-confirm").assertIsEnabled();true}.getOrDefault(false)}
                click("transaction-mark-commitment-confirm");await("transaction-commitment-already")
                compose.onNodeWithTag("transaction-mark-commitment").assertDoesNotExist()
                click("beta-page-back")
                compose.onNode(hasContentDescription(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_settings)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
                val counts=context.getString(com.baraa.masroof.lab.beta.R.string.beta_commitment_settings_commitments_hub_subtitle,1,0)
                compose.waitUntil(15000) {compose.onAllNodesWithText(counts).fetchSemanticsNodes().isNotEmpty()}
                click("manage-COMMITMENTS");await("commitments-list")
                compose.onNodeWithText("Source subscription").assertExists()
                compose.onNode(hasContentDescription(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_home)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
                await("dashboard-ready")
                compose.onNodeWithText(context.getString(com.baraa.masroof.lab.beta.R.string.beta_home_commitments_shortcut)).performScrollTo().performClick()
                compose.onNodeWithText("Source subscription").performScrollTo().performClick()
                await("commitment-editor")
                compose.onNodeWithTag("commitment-name").performTextReplacement("Discarded draft")
                scenario.recreate();await("commitment-editor")
                compose.onNodeWithTag("commitment-name").assertTextEquals("Discarded draft")
                click("beta-page-back");await("dashboard-ready")
                compose.onNodeWithText("Source subscription").performScrollTo().performClick()
                await("commitment-editor")
                compose.onNodeWithTag("commitment-name").assertTextEquals("Source subscription")
                compose.onNodeWithTag("commitment-name").performTextReplacement("Saved subscription")
                click("commitment-save")
                compose.waitUntil(15000) {runCatching {compose.onNodeWithTag("commitment-save").assertIsEnabled();true}.getOrDefault(false)}
                click("beta-page-back");await("dashboard-ready")
                compose.onNodeWithText("Saved subscription").assertExists()
            }
            val closed=CountDownLatch(1);BetaIo.execute {closed.countDown()};assertTrue(closed.await(15,TimeUnit.SECONDS))
            BetaContainer(context).use {beta ->
                val row=beta.dashboardControls().read().draft.commitments.single()
                assertEquals(key.transactionId,row.sourceTransactionId);assertEquals(Money.of("42.35",Currency.SAR),row.amount)
                assertEquals(at.atZone(zone).toLocalDate(),row.transactionDate)
                assertEquals(before,beta.financialHistory(key))
            }
        } finally {BetaContainer.resetOffline(context)}
    }
}
