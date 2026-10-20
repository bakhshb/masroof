package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.posting.PostingKey
import com.baraa.masroof.lab.finance.value.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic isolated emulator data only; the empty-inbox setup exercises the Android provider. */
class BetaCleanJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    @org.junit.Before fun allowSyntheticInbox() {
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val packageName=context.packageName
        automation.grantRuntimePermission(packageName,android.Manifest.permission.READ_SMS)
        // A permission grant does not clear a UID-level deny left by an AppOps denial test.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("appops set --uid $packageName READ_SMS allow")).use {it.readBytes()}
    }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val bank = BankId("BANK_ALJAZIRA")
    private fun await(tag: String) { compose.waitUntil(20000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }; compose.waitForIdle() }
    private fun click(tag: String) {
        await(tag)
        compose.waitUntil(20000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false) }
        val node=compose.onNodeWithTag(tag); if(tag in setOf("asset-save","asset-cancel","ownership-continue","beta-page-back")) node.assertIsDisplayed().performClick() else node.performScrollTo().performClick()
    }
    private fun drain() { val done = CountDownLatch(1); BetaIo.execute { done.countDown() }; assertTrue(done.await(20, TimeUnit.SECONDS)) }

    private fun capture(name: String) {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /sdcard/beta-dashboard-screenshots")).use { it.readBytes() }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /sdcard/beta-dashboard-screenshots/$name.png")).use { it.readBytes() }
    }

    @Test fun optionalSetupPersistsAndSettingsSeparateFinancialManagementFromLabActions() {
        BetaContainer.resetOffline(context)
        try {
            ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
                click("beta-start-continue"); await("onboarding-IMPORT")
                capture("m10-bank-step")
                scenario.recreate(); await("onboarding-IMPORT")
                await("history-start")
                click("history-start"); click("history-close"); await("onboarding-ASSETS")
                click("onboarding-manage"); await("beta-management-settings")
                compose.onNodeWithTag("beta-theme-DARK").assertDoesNotExist()
                compose.onNodeWithTag("beta-settings-reset").assertDoesNotExist()
                click("manage-ACCOUNTS"); click("manage-add-BANK_ALJAZIRA")
                compose.onNodeWithTag("asset-last4").performScrollTo().performTextInput("1234")
                compose.onNodeWithTag("asset-save").assertIsDisplayed().assertIsNotEnabled()
                compose.onNodeWithTag("asset-last4").performImeAction()
                compose.waitForIdle()
                click("asset-owned-confirm"); click("asset-save")
                await("manage-add-BANK_ALJAZIRA")
                click("beta-page-back"); await("beta-management-settings")
                click("beta-page-back"); await("onboarding-ASSETS")
                click("ownership-continue"); await("onboarding-REVIEW")
                scenario.recreate(); await("onboarding-REVIEW")
                click("onboarding-next"); await("dash-refresh")
                val settings = compose.onNode(hasText(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_settings)) and hasAnyAncestor(hasTestTag("beta-bottom-navigation")))
                settings.performClick(); await("beta-management-settings"); capture("m10-management-settings")
                compose.onNodeWithTag("beta-developer-tools").assertDoesNotExist()
                compose.onNodeWithTag("beta-settings-backup").assertDoesNotExist()
                click("manage-COMMITMENTS"); await("controls-add")
                compose.onNodeWithTag("controls-salary").assertDoesNotExist()
                click("beta-page-back"); click("beta-page-back"); await("dash-refresh")
            }
            drain()
            BetaContainer(context).use {
                assertEquals(BetaOnboardingStep.COMPLETE, it.onboardingStep())
                assertEquals(1, it.assets().size)
                assertTrue(it.evidencePage().isEmpty()); assertEquals(true,it.historySession()?.finished)
            }
            ActivityScenario.launch(BetaActivity::class.java).use { await("dash-refresh"); compose.onNodeWithTag("onboarding-IMPORT").assertDoesNotExist() }
        } finally { drain(); BetaContainer.resetOffline(context) }
    }

    @Test fun emptyHomeResumesSetupWithoutDeletingEvidence() {
        BetaContainer.resetOffline(context)
        try {
            BetaContainer(context).use { beta ->
                beta.acknowledgeStart(); beta.setBankEnabled(bank, true)
                beta.putMarker("retained", "synthetic")
                beta.importEvidence(EvidenceRequest("setup-recovery", EvidenceOrigin.EXPLICIT_SAMPLE), listOf(
                    EvidenceMessage("setup-recovery", "AlJazira", "Cash withdrawal\nFrom account: 1234\nAmount: 42 SAR\nDate: 2026-10-18 12:00",
                        Instant.parse("2026-10-18T12:00:00Z"), ReceiptTimeSource.USER_SUPPLIED)))
            }
            ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
                await("home-setup-help"); capture("setup-empty-home")
                click("home-setup"); await("onboarding-IMPORT"); await("history-start")
                scenario.recreate(); await("onboarding-IMPORT")
                capture("setup-import-help")
                click("history-close"); await("onboarding-ASSETS")
                compose.onNodeWithTag("ownership-continue").assertIsDisplayed().assertIsNotEnabled()
                click("ownership-BANK_ALJAZIRA-ACCOUNT-1234-external")
                click("ownership-continue"); await("onboarding-REVIEW")
                capture("setup-approval-help")
                click("onboarding-next"); await("home-setup")
            }
            drain()
            BetaContainer(context).use { beta ->
                assertEquals(1, beta.markerCount())
                assertEquals(1, beta.evidencePage().size)
                assertNull(beta.historySession())
                assertNull(beta.financialPosting(PostingKey(Environment.BETA, "evidence:${beta.evidencePage().single().id}")))
            }
        } finally { drain(); BetaContainer.resetOffline(context) }
    }

    @Test fun settingsResetRequiresConfirmationAndReturnsToFreshSetup() {
        BetaContainer.resetOffline(context)
        val productionProbe = context.getSharedPreferences("setup-production-probe", Context.MODE_PRIVATE)
        productionProbe.edit().putString("retained", "production sentinel").commit()
        try {
            BetaContainer(context).use { beta ->
                beta.acknowledgeStart(); beta.putMarker("retained", "synthetic")
                beta.setBankEnabled(bank, true); beta.backup()
            }
            ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
                await("dash-refresh")
                compose.onNode(hasText(context.getString(com.baraa.masroof.lab.beta.R.string.beta_nav_settings)) and
                    hasAnyAncestor(hasTestTag("beta-bottom-navigation"))).performClick()
                click("manage-ABOUT")
                click("settings-reset-lab"); await("settings-reset-confirm"); capture("setup-reset-confirm")
                compose.onNodeWithTag("settings-reset-cancel").assertIsEnabled().performClick(); compose.onNodeWithTag("settings-reset-confirm").assertDoesNotExist()
                drain(); BetaContainer(context).use { assertEquals(1, it.markerCount()); assertTrue(it.settings().backupAvailable) }
                click("settings-reset-lab")
                // Exercise the Activity back callback after the confirmation is committed.
                // A raw shell key can reach the previous window during Android 16 focus transfer.
                compose.waitForIdle()
                scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                compose.waitUntil(20000) { compose.onAllNodesWithTag("settings-reset-confirm").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("settings-reset-lab").assertExists()
                click("settings-reset-lab"); compose.onNodeWithTag("settings-reset-confirm").assertIsEnabled().performClick(); await("beta-start-continue")
                click("beta-start-continue"); await("onboarding-IMPORT")
                scenario.recreate(); await("onboarding-IMPORT")
            }
            drain()
            BetaContainer(context).use { beta ->
                assertEquals(0, beta.markerCount()); assertTrue(beta.assets().isEmpty()); assertTrue(beta.evidencePage().isEmpty())
                assertFalse(beta.settings().backupAvailable); assertNull(beta.historySession())
                assertEquals(BetaOnboardingStep.IMPORT, beta.onboardingStep())
            }
            assertEquals("production sentinel", productionProbe.getString("retained", null))
        } finally { drain(); BetaContainer.resetOffline(context); productionProbe.edit().clear().commit() }
    }

    @Test fun currentEngineDiscoveryAutomaticallyPostsClearEvidenceOnce() {
        BetaContainer.resetOffline(context)
        lateinit var evidenceId: String
        try {
            BetaContainer(context).use { beta ->
                beta.beginOnboarding(); beta.setBankEnabled(bank, true); beta.advanceOnboarding(BetaOnboardingStep.PERMISSION)
                val at = Instant.parse("2026-10-18T12:00:00Z")
                val message = EvidenceMessage("clean-journey", "AlJazira", "Cash withdrawal\nFrom account: 1234\nAmount: 42 SAR\nDate: 2026-10-18 12:00", at, ReceiptTimeSource.USER_SUPPLIED)
                evidenceId = (beta.importEvidence(EvidenceRequest("clean-journey", EvidenceOrigin.EXPLICIT_SAMPLE), listOf(message)).single() as EvidenceResult.Stored).summary.id
                assertNull(requireNotNull(beta.financialApprovalPreview(evidenceId)).blockedReason)
                assertNull(beta.financialPosting(PostingKey(Environment.BETA, "evidence:$evidenceId")))
                // A completed, synthetic import checkpoint. This test never reads the phone inbox.
                com.baraa.masroof.lab.beta.data.PreferenceHistorySessionStore(
                    context.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE)).save(
                    com.baraa.masroof.lab.beta.application.history.HistorySession(at.toEpochMilli(), at.toEpochMilli() + 86_400_000,
                        setOf(bank), scanned = 1, stored = 1, finished = true))
            }
            ActivityScenario.launch(BetaActivity::class.java).use {
                await("onboarding-IMPORT"); await("history-close")
                compose.onNodeWithTag("history-review-0").assertDoesNotExist()
                compose.onNodeWithTag("history-filter-review").assertDoesNotExist()
                click("history-close")
                await("onboarding-ASSETS"); capture("setup-accounts-before-messages")
                compose.onNodeWithTag("ownership-continue").assertIsDisplayed().assertIsNotEnabled()
                click("ownership-BANK_ALJAZIRA-ACCOUNT-1234-owned")
                click("ownership-continue")
                await("onboarding-REVIEW"); click("onboarding-next"); await("dash-refresh")
            }
            drain()
            val key = PostingKey(Environment.BETA, "evidence:$evidenceId")
            BetaContainer(context).use { beta ->
                assertEquals(Money.of("42", Currency.SAR), requireNotNull(beta.financialPosting(key)).command.amount)
                assertEquals(1, beta.financialHistory(key).size)
                assertEquals(BetaOnboardingStep.COMPLETE, beta.onboardingStep())
            }
            ActivityScenario.launch(BetaActivity::class.java).use { await("dash-refresh") }
            drain()
            BetaContainer(context).use { assertEquals(1, it.financialHistory(key).size) }
        } finally { drain(); BetaContainer.resetOffline(context) }
    }
}
