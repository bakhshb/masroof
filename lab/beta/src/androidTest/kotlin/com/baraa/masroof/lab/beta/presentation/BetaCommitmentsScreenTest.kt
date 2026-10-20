package com.baraa.masroof.lab.beta.presentation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.value.*
import java.time.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BetaCommitmentsScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private class Controls: BetaDashboardControls,BetaCommitmentHistorySource {
        var config=BetaDashboardConfiguration(1,BetaDashboardConfigurationDraft(commitments=listOf(
            BetaRecurringCommitment("one","Original",Money.of("200",Currency.SAR),5,YearMonth.of(2026,10),transactionDate=LocalDate.of(2026,10,5),sourceTransactionId="source"))))
        var hold: CompletableDeferred<Unit>?=null
        var saves=0
        var throwOnSave=false
        override suspend fun read()=config
        override suspend fun commitmentHistory()=emptyList<BetaCommitmentHistoryEntry>()
        override suspend fun save(expectedRevision: Long,draft: BetaDashboardConfigurationDraft): BetaDashboardConfigurationWrite {
            saves++;hold?.await();if(throwOnSave) error("Synthetic unavailable storage")
            if(expectedRevision!=config.revision) return BetaDashboardConfigurationWrite.Conflict(config)
            config=BetaDashboardConfiguration(config.revision+1,draft)
            return BetaDashboardConfigurationWrite.Saved(config)
        }
    }
    private fun await(tag: String)=compose.waitUntil(15000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    private fun click(tag: String) {
        await(tag)
        val node=compose.onNodeWithTag(tag)
        if(tag=="commitments-retry") node.assertIsDisplayed().performClick() else node.performScrollTo().performClick()
    }
    @Test fun restoredDraftRetainsItsRevisionAndCannotOverwriteAConcurrentEdit() {
        val controls=Controls();val restorer=StateRestorationTester(compose)
        restorer.setContent {MaterialTheme {BetaCommitmentsPanel(controls,false,{},{})}}
        click("commitment-edit-one")
        compose.onNodeWithTag("commitment-name").performTextReplacement("Uncommitted")
        compose.runOnIdle {controls.config=controls.config.copy(revision=2)}
        restorer.emulateSavedInstanceStateRestore();await("commitment-editor")
        compose.onNodeWithTag("commitment-name").assertTextContains("Uncommitted")
        click("commitment-save");await("commitments-error")
        assertEquals(0,controls.saves);assertEquals("Original",controls.config.draft.commitments.single().label)
        click("commitments-retry");await("commitments-list")
        compose.onNodeWithText("Original").assertExists()
        compose.onNodeWithText("Uncommitted").assertDoesNotExist()
    }
    @Test fun failedWriteKeepsTheDraftForRetryRatherThanDroppingTheEditor() {
        val controls=Controls()
        compose.setContent {MaterialTheme {BetaCommitmentsPanel(controls,false,{},{})}}
        click("commitment-edit-one")
        compose.onNodeWithTag("commitment-name").performTextReplacement("Retry me")
        compose.runOnIdle {controls.throwOnSave=true}
        click("commitment-save");await("commitments-error")
        compose.onNodeWithTag("commitment-name").assertTextContains("Retry me")
        assertEquals("Original",controls.config.draft.commitments.single().label)
        compose.runOnIdle {controls.throwOnSave=false}
        click("commitment-save")
        compose.waitUntil {controls.config.draft.commitments.single().label=="Retry me"}
        assertEquals(2,controls.saves)
    }
    @Test fun pendingSaveBlocksEditingAndBackThenReturnsToTheSameCommitment() {
        val controls=Controls();var exited=0
        compose.setContent {MaterialTheme {
            BackHandler {exited++}
            BetaCommitmentsPanel(controls,false,{},{})
        }}
        click("commitment-edit-one")
        compose.onNodeWithTag("commitment-name").performTextReplacement("Saved")
        compose.runOnIdle {controls.hold=CompletableDeferred()}
        click("commitment-save")
        compose.onNodeWithTag("commitment-save").assertIsNotEnabled()
        compose.onNodeWithTag("commitment-name").assertIsNotEnabled()
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("commitment-editor").assertExists();assertEquals(0,exited)
        compose.runOnIdle {controls.hold!!.complete(Unit)}
        compose.waitUntil {controls.config.draft.commitments.single().label=="Saved"}
        compose.waitForIdle()
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
        await("commitments-list");compose.onNodeWithText("Saved").assertExists();assertEquals(0,exited)
        assertEquals(1,controls.saves)
    }
}
