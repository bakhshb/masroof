package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.BetaEnvironment
import com.baraa.masroof.lab.beta.BetaIo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BetaSettingsActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun actualActivityRestoresSettingsRouteThemeAndUncommittedConfirmationAfterRecreation() {
        BetaContainer.resetOffline(context)
        BetaContainer(context).use { it.putMarker("synthetic", "keep") }
        ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
            compose.waitUntil(15000) { compose.onAllNodesWithTag("beta-start-continue").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithTag("beta-start-continue").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("beta-tools").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithTag("beta-reset").assertDoesNotExist()
            compose.onNode(hasText(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_settings)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
            compose.onNodeWithTag("manage-ABOUT").performScrollTo().performClick()
            compose.onNodeWithTag("settings-developer-tools").performScrollTo().performClick()
            compose.onNodeWithTag("beta-developer-tools").performScrollTo().performClick()
            compose.onNodeWithTag("beta-theme-DARK").performScrollTo().performClick()
            compose.waitUntil(10000) { context.getSharedPreferences(BetaEnvironment.PREFS_NAME, 0).getString("beta_ui_theme", null) == "DARK" }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("beta-settings-busy").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("beta-settings-reset").performScrollTo().performClick()
            scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("beta-settings-confirm").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithTag("beta-settings-cancel").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("beta-settings-panel").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("beta-settings-panel").assertExists()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("beta-theme-DARK").fetchSemanticsNodes().singleOrNull()?.config
                ?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == true }
            compose.onNodeWithTag("beta-theme-DARK").performScrollTo().assertIsNotEnabled()
        }
        waitForClose()
        BetaContainer(context).use { assertEquals(1, it.markerCount()); assertEquals("DARK", it.settings().theme) }
    }

    @Test fun malformedRecoveryShowsSafeRetryAndDoesNotCreateReplacementData() {
        BetaContainer.resetOffline(context)
        BetaContainer(context).use { it.putMarker("synthetic", "keep") }
        val journal = File(context.filesDir, "beta_recovery_v2/restore_journal")
        journal.writeText("corrupted journal")
        ActivityScenario.launch(BetaActivity::class.java).use {
            compose.waitUntil(15000) { compose.onAllNodesWithTag("beta-startup-failure").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithTag("beta-settings").assertDoesNotExist()
            assertTrue(context.getDatabasePath(BetaEnvironment.DATABASE_NAME).length() > 0)
            journal.delete()
            compose.onNodeWithTag("beta-startup-retry").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("beta-start-continue").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithTag("beta-start-continue").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("beta-tools").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        }
        waitForClose()
        BetaContainer(context).use { assertEquals(1, it.markerCount()) }
    }
    @Test fun approvedCardTitleRenameRestoresDraftAndPreservesIdentityOnReopen() {
        BetaContainer.resetOffline(context)
        val bank=com.baraa.masroof.lab.finance.value.BankId("BANK_ALJAZIRA")
        val id=com.baraa.masroof.lab.finance.asset.AssetId(com.baraa.masroof.lab.finance.value.Environment.BETA,bank,"rename-card")
        val original=BetaContainer(context).use {beta ->
            beta.setBankEnabled(bank,true)
            beta.saveManualAsset(com.baraa.masroof.lab.beta.application.ManualAssetDraft(id,
                com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_CARD,"7271",id.copy(localId="facility"),
                com.baraa.masroof.lab.finance.asset.CreditCardRole.STANDALONE,true,createCreditFacility=true,
                metadata=com.baraa.masroof.lab.beta.application.BetaAssetMetadata(displayName="Original card",network="VISA")))
        }
        try {ActivityScenario.launch(BetaActivity::class.java).use {scenario ->
            compose.waitUntil(15000) {compose.onAllNodesWithTag("beta-start-continue").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("beta-start-continue").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("dash-refresh").fetchSemanticsNodes().isNotEmpty()}
            compose.onNode(hasText(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_settings)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
            compose.onNodeWithTag("manage-CARDS").performScrollTo().performClick()
            compose.onNodeWithText("Original card •• 7271").performScrollTo().performClick()
            compose.onNodeWithTag("asset-rename-name").performTextReplacement("Travel card")
            scenario.recreate()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("asset-rename-name").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("asset-rename-name").assertTextContains("Travel card")
            compose.onNodeWithTag("asset-rename-save").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("asset-rename-save").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithText("Travel card •• 7271").assertExists()
            compose.waitForIdle()
            val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "screencap -p /sdcard/beta-dashboard-screenshots/approved-card-registry.png")).use {it.readBytes()}
        }
        waitForClose()
        BetaContainer(context).use {beta ->
            assertEquals(original,beta.assets().single {it.id==id})
            assertEquals("Travel card",beta.assetMetadata()[id]?.displayName)
            assertEquals("VISA",beta.assetMetadata()[id]?.network)
            assertEquals(2,beta.assets().size)
        }} finally {BetaContainer.resetOffline(context)}
    }
    private fun waitForClose() { val done = CountDownLatch(1); BetaIo.execute { done.countDown() }; assertTrue(done.await(15, TimeUnit.SECONDS)) }
}
