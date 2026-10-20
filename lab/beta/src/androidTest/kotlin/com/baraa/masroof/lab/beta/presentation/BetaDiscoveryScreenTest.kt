package com.baraa.masroof.lab.beta.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.baraa.masroof.lab.beta.application.BetaAssetProposal
import com.baraa.masroof.lab.beta.application.ManualAssetDraft
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BetaDiscoveryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun rtlProposalRestoresUnconfirmedDraftAndPreservesExplicitStableIdentity() {
        val bank = BankId("BANK_ALJAZIRA")
        val proposal = BetaAssetProposal(AssetId(Environment.BETA, bank, "opaque-proposal"), AssetKind.ACCOUNT, "1234", "evidence", "revision")
        var saved: ManualAssetDraft? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { BetaShell(0, false, editor = {
                    BetaAssetEditor(bank, null, emptyList(), false, false, onSave = { saved = it }, onCancel = {}, proposal = proposal)
                }, onReturn = {}, onReset = {}, onBackup = {}, onRestore = {}) }
            }
        }
        compose.onNodeWithTag("asset-last4").performScrollTo().assertTextContains("1234")
        compose.onNodeWithTag("asset-save").assertIsDisplayed().assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("asset-save").assertIsDisplayed().assertIsNotEnabled()
        assertNull(saved)
        compose.onNodeWithTag("asset-owned-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("asset-save").assertIsDisplayed().performClick()
        assertEquals(proposal.id, saved!!.id)
        assertTrue(saved!!.ownedConfirmed)
        assertNull(saved!!.parentId)
    }
    @Test fun repeatedMessagesProduceOneOwnershipRowAndExternalDecisionPersists() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.baraa.masroof.lab.beta.BetaContainer.resetOffline(context)
        try { com.baraa.masroof.lab.beta.BetaContainer(context).use { beta ->
            val bank = BankId("BANK_ALJAZIRA")
            beta.setBankEnabled(bank, true)
            beta.importEvidence(com.baraa.masroof.lab.beta.application.evidence.EvidenceRequest("synthetic", com.baraa.masroof.lab.beta.application.evidence.EvidenceOrigin.EXPLICIT_SAMPLE),
                (1..55).map { com.baraa.masroof.lab.beta.application.evidence.EvidenceMessage("$it", "AlJazira",
                    "Cash withdrawal\nFrom account: 1234\nAmount: $it SAR\nDate: 2026-10-18 12:00", java.time.Instant.parse("2026-10-18T12:00:00Z").plusSeconds(it.toLong()),
                    com.baraa.masroof.lab.beta.application.evidence.ReceiptTimeSource.USER_SUPPLIED) })
            val panelBusy = java.util.concurrent.atomic.AtomicBoolean(false)
            val tag = "ownership-BANK_ALJAZIRA-ACCOUNT-1234"
            val arabic = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag("ar")); setLayoutDirection(java.util.Locale.forLanguageTag("ar"))
            })
            val isRtl = java.util.concurrent.atomic.AtomicBoolean(false)
            val restoration = StateRestorationTester(compose)
            restoration.setContent { CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides arabic,
                androidx.compose.ui.platform.LocalConfiguration provides arabic.resources.configuration, LocalLayoutDirection provides LayoutDirection.Rtl) {
                com.baraa.masroof.ui.theme.MasroofTheme {
                    val direction = LocalLayoutDirection.current
                    androidx.compose.runtime.SideEffect { isRtl.set(direction == LayoutDirection.Rtl) }
                    BetaShell(0, false, editor = {
                BetaDiscoveryPanel(beta, beta.assets(), onClose = {}, onBusyChanged = { panelBusy.set(it) })
            }, onReturn = {}, onReset = {}, onBackup = {}, onRestore = {}) } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
            compose.runOnIdle { assertTrue("Arabic ownership must use RTL inside the shared theme", isRtl.get()) }
            compose.onNodeWithTag("proposal-next").assertDoesNotExist()
            compose.onNodeWithTag("asset-save").assertDoesNotExist()
            compose.waitForIdle()
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /sdcard/beta-dashboard-screenshots/ownership-ar.png")).use { it.readBytes() }
            compose.onNodeWithTag("$tag-external").performScrollTo().performClick()
            compose.waitUntil(10_000) { beta.ownershipSnapshot().candidates.single().status == com.baraa.masroof.lab.beta.application.BetaOwnershipStatus.EXTERNAL }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
            assertTrue(beta.assets().isEmpty())
            compose.waitUntil(10_000) { !panelBusy.get() }
        } } finally { com.baraa.masroof.lab.beta.BetaContainer.resetOffline(context) }
    }

    @Test fun collidingAssetsRequireSelectionAndAStaleChoiceCannotWrite() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.baraa.masroof.lab.beta.BetaContainer.resetOffline(context)
        try { com.baraa.masroof.lab.beta.BetaContainer(context).use { beta ->
            val bank = BankId("BANK_ALJAZIRA")
            beta.setBankEnabled(bank, true)
            fun capture(id: String) = beta.importEvidence(com.baraa.masroof.lab.beta.application.evidence.EvidenceRequest(id, com.baraa.masroof.lab.beta.application.evidence.EvidenceOrigin.EXPLICIT_SAMPLE),
                listOf(com.baraa.masroof.lab.beta.application.evidence.EvidenceMessage(id, "AlJazira", "Cash withdrawal\nFrom account: 1234\nAmount: 42 SAR\nDate: 2026-10-18 12:00",
                    java.time.Instant.parse("2026-10-18T12:00:00Z"), com.baraa.masroof.lab.beta.application.evidence.ReceiptTimeSource.USER_SUPPLIED)))
            capture("first")
            beta.registerAsset(OwnedAsset(AssetId(Environment.BETA, bank, "first"), AssetKind.ACCOUNT, "1234"))
            beta.registerAsset(OwnedAsset(AssetId(Environment.BETA, bank, "second"), AssetKind.ACCOUNT, "1234"))
            val panelBusy = java.util.concurrent.atomic.AtomicBoolean(false)
            val tag = "ownership-BANK_ALJAZIRA-ACCOUNT-1234"
            compose.setContent { MaterialTheme { BetaShell(0, false, editor = {
                BetaDiscoveryPanel(beta, beta.assets(), onClose = {}, onBusyChanged = { panelBusy.set(it) })
            }, onReturn = {}, onReset = {}, onBackup = {}, onRestore = {}) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("$tag-owned").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("$tag-owned").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("$tag-choice-0").performScrollTo().performClick()
            capture("later") // The displayed decision is now stale.
            compose.onNodeWithTag("$tag-owned").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("ownership-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("$tag-owned").assertIsNotEnabled()
            assertEquals(2, beta.assets().size)
            assertEquals(com.baraa.masroof.lab.beta.application.BetaOwnershipStatus.UNKNOWN, beta.ownershipSnapshot().candidates.single().status)
            compose.onNodeWithTag("$tag-choice-0").performScrollTo().performClick()
            compose.onNodeWithTag("$tag-owned").performScrollTo().performClick()
            compose.waitUntil(10_000) { beta.ownershipSnapshot().unresolvedCount == 0 && !panelBusy.get() }
            assertEquals(2, beta.assets().size)
        } } finally { com.baraa.masroof.lab.beta.BetaContainer.resetOffline(context) }
    }
    @Test fun suggestedCreditCardCreatesPrivateStructureOnlyAfterExplicitSave() {
        val bank = BankId("BANK_ALJAZIRA")
        val proposal = BetaAssetProposal(AssetId(Environment.BETA, bank, "opaque-card"), AssetKind.CREDIT_CARD, "1234", "evidence", "revision")
        var saved: ManualAssetDraft? = null
        compose.setContent { MaterialTheme { BetaShell(0, false, editor = {
            BetaAssetEditor(bank, null, emptyList(), false, false, onSave = { saved = it }, onCancel = {}, proposal = proposal)
        }, onReturn = {}, onReset = {}, onBackup = {}, onRestore = {}) } }
        compose.onNodeWithTag("asset-owned-confirm").performScrollTo().performClick()
        assertNull(saved) // Opening or checking ownership alone never creates the structural facility.
        compose.onNodeWithTag("asset-save").assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(requireNotNull(saved).createCreditFacility)
        assertEquals(CreditCardRole.STANDALONE, requireNotNull(saved).role)
        assertNotNull(requireNotNull(saved).parentId)
    }
}
