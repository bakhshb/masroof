package com.baraa.masroof.lab.beta.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.FacilitySummary
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.lab.finance.value.Environment
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger

class BetaDashboardControlsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val month = YearMonth.of(2026, 10)
    private class Controls : BetaDashboardControls {
        var state = BetaDashboardConfiguration(1, BetaDashboardConfigurationDraft())
        var hold: CompletableDeferred<Unit>? = null
        var conflict = false
        val saves = AtomicInteger()
        override suspend fun read() = state
        override suspend fun save(expectedRevision: Long, draft: BetaDashboardConfigurationDraft): BetaDashboardConfigurationWrite {
            saves.incrementAndGet(); hold?.await()
            if (conflict || expectedRevision != state.revision) return BetaDashboardConfigurationWrite.Conflict(state.copy(revision = state.revision + 1))
            state = BetaDashboardConfiguration(state.revision + 1, draft)
            return BetaDashboardConfigurationWrite.Saved(state)
        }
    }
    private fun show(controls: BetaDashboardControls, facilities: List<FacilitySummary> = emptyList()) {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BetaDashboardConfigurationPanel(controls, facilities, true, {}, {})
        } } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("controls-salary").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun confirm() {
        // The confirmation is a separate dialog window, installed after Apply changes state.
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("controls-confirm").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        node("controls-confirm").assertIsDisplayed().performClick()
    }
    @Test fun statementEditorKeepsSameLocalIdAtDifferentBanksSeparate() {
        val a = AssetId(Environment.BETA, BankId("A"), "same")
        val b = AssetId(Environment.BETA, BankId("B"), "same")
        val controls = Controls()
        controls.state = controls.state.copy(draft = BetaDashboardConfigurationDraft(statements = listOf(BetaFacilityCycle(a, 20))))
        show(controls, listOf(FacilitySummary(a, null), FacilitySummary(b, null)))
        node("controls-statement-B-same").performScrollTo().performClick()
        node("controls-day").performTextInput("14")
        node("dash-sheet-apply").performClick(); confirm()
        compose.waitUntil { compose.onAllNodesWithTag("dash-sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(BetaFacilityCycle(a, 20), BetaFacilityCycle(b, 14)), controls.state.draft.statements)
        assertEquals(1, controls.saves.get())
    }
    @Test fun cancelNeverWritesAndConfirmedBusySaveBlocksDismissalAndDuplicateSubmission() {
        val controls = Controls(); show(controls)
        node("controls-salary").performScrollTo().performClick()
        node("controls-day").performTextInput("27")
        node("dash-sheet-cancel").performClick()
        assertEquals(0, controls.saves.get()); assertNull(controls.state.draft.salary)
        node("controls-salary").performScrollTo().performClick()
        node("controls-day").performTextInput("27")
        node("dash-sheet-apply").performClick()
        assertEquals(0, controls.saves.get())
        controls.hold = CompletableDeferred()
        confirm()
        compose.waitUntil { controls.saves.get() == 1 }
        node("controls-confirm").assertIsNotEnabled()
        compose.onAllNodesWithTag("dash-sheet-cancel").onFirst().assertIsNotEnabled()
        compose.runOnIdle { controls.hold!!.complete(Unit) }
        compose.waitUntil { compose.onAllNodesWithTag("dash-sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(27, controls.state.draft.salary!!.day); assertEquals(1, controls.saves.get())
    }
    @Test fun staleRevisionRequiresDiscardAndReloadRatherThanOverwritingNewerConfiguration() {
        val controls = Controls(); show(controls)
        node("controls-salary").performScrollTo().performClick(); node("controls-day").performTextInput("27")
        node("dash-sheet-apply").performClick(); controls.conflict = true; confirm()
        compose.waitUntil { compose.onAllNodesWithTag("controls-conflict").fetchSemanticsNodes().isNotEmpty() }
        node("dash-sheet-apply").assertIsNotEnabled(); assertNull(controls.state.draft.salary)
        node("controls-reload").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithTag("dash-sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, controls.saves.get())
    }
    @Test fun restoredDraftKeepsOriginalRevisionAndDetectsNewerSettingsAfterRecreation() {
        val controls = Controls()
        val restorer = StateRestorationTester(compose)
        restorer.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BetaDashboardConfigurationPanel(controls, emptyList(), true, {}, {})
        } } }
        compose.waitUntil { compose.onAllNodesWithTag("controls-salary").fetchSemanticsNodes().isNotEmpty() }
        node("controls-salary").performScrollTo().performClick(); node("controls-day").performTextInput("27")
        controls.state = controls.state.copy(revision = 2)
        restorer.emulateSavedInstanceStateRestore()
        compose.waitUntil { compose.onAllNodesWithTag("controls-day").fetchSemanticsNodes().isNotEmpty() }
        node("controls-day").assertTextContains("27")
        node("controls-conflict").assertExists(); node("dash-sheet-apply").assertIsNotEnabled()
        assertEquals(0, controls.saves.get())
        node("dash-sheet-cancel").performClick()
    }
    @Test fun cycleSettingsKeepCommitmentsAndDoNotExposeTheRemovedGenericEditor() {
        val controls=Controls()
        val row=BetaRecurringCommitment("existing","Existing",com.baraa.masroof.lab.finance.value.Money.of("100",com.baraa.masroof.lab.finance.value.Currency.SAR),5,month,
            recurrence=BetaCommitmentRecurrence.WEEKLY,transactionDate=month.atDay(5),sourceTransactionId="source")
        controls.state=controls.state.copy(draft=controls.state.draft.copy(commitments=listOf(row)))
        show(controls)
        node("controls-add").assertDoesNotExist();node("controls-edit-existing").assertDoesNotExist()
        node("controls-salary").performScrollTo().performClick();node("controls-day").performTextInput("27")
        node("dash-sheet-apply").performClick();confirm()
        compose.waitUntil {compose.onAllNodesWithTag("dash-sheet").fetchSemanticsNodes().isEmpty()}
        assertEquals(listOf(row),controls.state.draft.commitments)
        assertEquals(27,controls.state.draft.salary?.day)
    }
}
