package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.verification.SyntheticFinancialHistory
import com.baraa.masroof.lab.finance.value.*
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real settings/navigation/SQLite proof, separate from frozen fixture screenshots. */
class BetaCommitmentsActivityTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun await(tag: String)=compose.waitUntil(15000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
    private fun click(tag: String) {
        await(tag)
        compose.waitUntil(15000) {runCatching {compose.onNodeWithTag(tag).assertIsEnabled();true}.getOrDefault(false)}
        val node=compose.onNodeWithTag(tag)
        if(tag in setOf("beta-page-back","commitment-delete-cancel","commitment-delete-confirm")) node.assertIsDisplayed().performClick() else node.performScrollTo().performClick()
    }
    private fun closed() {val latch=CountDownLatch(1);BetaIo.execute {latch.countDown()};assertTrue(latch.await(15,TimeUnit.SECONDS))}
    @Test fun actualSettingsDraftCancelSaveRecreatePauseResumeHistoryAndDeletePreserveLedger()=runBlocking {
        BetaContainer.resetOffline(context)
        try {
            BetaContainer(context).use {beta ->
                SyntheticFinancialHistory.seed(beta,40)
                val controls=beta.dashboardControls();val config=controls.read()
                assertTrue(controls.save(config.revision,config.draft.copy(commitments=listOf(
                    BetaRecurringCommitment("internet","Synthetic internet",Money.of("200",Currency.SAR),5,YearMonth.of(2026,10),
                        transactionDate=LocalDate.of(2026,10,5),sourceTransactionId="posted-original")))) is BetaDashboardConfigurationWrite.Saved)
            }
            val before=SyntheticFinancialHistory.fingerprint(context)
            ActivityScenario.launch(BetaActivity::class.java).use {scenario ->
                await("beta-start-continue");click("beta-start-continue");await("dashboard-ready")
                compose.onNode(hasContentDescription(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_settings)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
                click("manage-COMMITMENTS");await("commitments-list")
                compose.onNodeWithTag("controls-add").assertDoesNotExist()
                click("commitment-edit-internet")
                compose.onNodeWithTag("commitment-name").performTextReplacement("Discard me")
                click("beta-page-back");await("commitments-list")
                compose.onNodeWithText("Synthetic internet").assertExists();compose.onNodeWithText("Discard me").assertDoesNotExist()
                click("commitment-edit-internet")
                compose.onNodeWithTag("commitment-name").performTextReplacement("Saved internet")
                compose.onNodeWithTag("commitment-amount").performTextReplacement("-1")
                compose.onNodeWithTag("commitment-save").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("commitment-amount").performScrollTo().performTextReplacement("250")
                click("commitment-recurrence");click("commitment-recurrence-1")
                scenario.recreate();await("commitment-editor")
                compose.onNodeWithTag("commitment-name").assertTextContains("Saved internet")
                compose.onNodeWithTag("commitment-amount").assertTextContains("250")
                click("commitment-save")
                compose.waitUntil(15000) {compose.onAllNodesWithTag("commitment-save").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)==false}
                click("beta-page-back");await("commitments-list")
                compose.onNodeWithText("Saved internet").assertExists()
                click("commitment-toggle-internet")
                compose.waitUntil(15000) {compose.onAllNodesWithTag("commitment-internet").fetchSemanticsNodes().isEmpty()}
                click("commitments-tab-DISABLED");await("commitment-internet")
                click("commitment-toggle-internet")
                compose.waitUntil(15000) {compose.onAllNodesWithTag("commitment-internet").fetchSemanticsNodes().isEmpty()}
                click("commitments-tab-RECORD");await("commitment-record-0")
                compose.onAllNodesWithText("Saved internet").assertCountEquals(3)
                click("commitment-record-0");await("commitment-editor")
                click("beta-page-back");click("commitments-tab-ACTIVE")
                click("commitment-delete-internet");click("commitment-delete-cancel")
                compose.onNodeWithTag("commitment-internet").assertExists()
            }
            closed()
            BetaContainer(context).use {beta ->
                val workflow=BetaCommitmentsWorkflow(beta.dashboardControls())
                val row=workflow.read().draft.commitments.single()
                assertEquals("Saved internet",row.label);assertEquals(Money.of("250",Currency.SAR),row.amount)
                assertEquals(BetaCommitmentRecurrence.WEEKLY,row.recurrence);assertTrue(row.active)
                assertEquals("posted-original",row.sourceTransactionId);assertEquals(1,row.pauseIntervals.size)
                assertNotNull(row.pauseIntervals.single().resumedAt);assertEquals(3,workflow.history().size)
            }
            ActivityScenario.launch(BetaActivity::class.java).use {
                // Start page may be shown again; settings location is intentionally re-entered.
                compose.waitUntil(15000) {compose.onAllNodesWithTag("beta-start-continue").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() || compose.onAllNodesWithTag("beta-bottom-navigation").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
                if(compose.onAllNodesWithTag("beta-start-continue").fetchSemanticsNodes().isNotEmpty()) click("beta-start-continue")
                await("beta-bottom-navigation")
                compose.onNode(hasContentDescription(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_settings)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
                click("manage-COMMITMENTS");await("commitment-internet")
                click("commitment-delete-internet");click("commitment-delete-confirm")
                compose.waitUntil(15000) {compose.onAllNodesWithTag("commitment-internet").fetchSemanticsNodes().isEmpty()}
            }
            closed()
            BetaContainer(context).use {beta ->assertTrue(beta.dashboardControls().read().draft.commitments.isEmpty())}
            val after=SyntheticFinancialHistory.fingerprint(context)
            before.keys().asSequence().filter {it !in setOf("preferences","beta_commitment","beta_audit_log")}.forEach {key ->
                assertEquals("Unchanged financial/storage fact: $key",before.get(key).toString(),after.get(key).toString())
            }
        } finally {BetaContainer.resetOffline(context)}
    }
}
