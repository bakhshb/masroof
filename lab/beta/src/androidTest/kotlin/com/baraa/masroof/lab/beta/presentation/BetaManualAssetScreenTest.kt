package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.ManualAssetDraft
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BetaManualAssetScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bank = BankId("BANK_ALJAZIRA")
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var beta: BetaContainer
    private fun id(value: String) = AssetId(Environment.BETA, bank, value)
    @Before fun open() {
        BetaContainer.resetOffline(context)
        beta = BetaContainer(context)
        beta.setBankEnabled(bank, true)
    }
    @After fun close() { beta.close(); BetaContainer.resetOffline(context) }
    private fun showEditor(existing: OwnedAsset? = null, onCancel: () -> Unit = {}, onSaved: () -> Unit = {}) {
        val assets = beta.assets()
        val metadata = existing?.let { beta.assetMetadata()[it.id] } ?: com.baraa.masroof.lab.beta.application.BetaAssetMetadata()
        compose.setContent {
            MaterialTheme {
                BetaShell(0, false, editor = {
                    BetaAssetEditor(bank, existing, assets, false, false,
                        onSave = { beta.saveManualAsset(it); onSaved() }, onCancel = onCancel, metadata = metadata, registryMetadata = beta.assetMetadata())
                }, onReturn = {}, onReset = {}, onBackup = {}, onRestore = {})
            }
        }
    }
    private fun confirmOwnership() {
        // Real IME animations are outside Compose's idle clock. Finish text entry
        // before scrolling/clicking so a changing keyboard inset cannot move the target.
        if (compose.onAllNodesWithTag("asset-owned-confirm").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("asset-last4").performImeAction()
            compose.waitForIdle()
            compose.onNodeWithTag("asset-owned-confirm").performScrollTo()
                .assertIsDisplayed().performClick().assertIsOn()
        }
        compose.onNodeWithTag("asset-save").assertIsDisplayed().assertIsEnabled()
    }
    @Test fun reselectingCurrentDebitKindAndNetworkPreservesItsAccount() {
        beta.saveManualAsset(ManualAssetDraft(id("account"), AssetKind.ACCOUNT, "1111", ownedConfirmed = true))
        val debit = beta.saveManualAsset(ManualAssetDraft(id("debit"), AssetKind.DEBIT_CARD, "3333", id("account"), ownedConfirmed = true,
            metadata = com.baraa.masroof.lab.beta.application.BetaAssetMetadata(network = "MADA")))
        showEditor(debit)
        compose.onNodeWithTag("asset-kind-DEBIT_CARD").performScrollTo().performClick()
        compose.onNodeWithTag("asset-network-MADA").performScrollTo().performClick()
        confirmOwnership()
        compose.onNodeWithTag("asset-save").performClick()
        assertEquals(debit, beta.assets().single { it.id == debit.id })
        assertEquals(2, beta.assets().size)
    }
    @Test fun reselectingCurrentPrimaryKindAndRoleDoesNotCreateAnotherFacility() {
        beta.saveManualAsset(ManualAssetDraft(id("facility"), AssetKind.CREDIT_FACILITY, "", ownedConfirmed = true))
        val primary = beta.saveManualAsset(ManualAssetDraft(id("primary"), AssetKind.CREDIT_CARD, "2222", id("facility"), CreditCardRole.PRIMARY, true))
        showEditor(primary)
        compose.onNodeWithTag("asset-kind-CREDIT_CARD").performScrollTo().performClick()
        compose.onNodeWithTag("asset-role-PRIMARY").performScrollTo().performClick()
        confirmOwnership()
        compose.onNodeWithTag("asset-save").performClick()
        assertEquals(primary, beta.assets().single { it.id == primary.id })
        assertEquals(2, beta.assets().size)
    }
    @Test fun cancelAndBackDiscardUnconfirmedDraftWithoutWrites() {
        var canceled = 0
        showEditor(onCancel = { canceled++ })
        compose.onNodeWithTag("asset-last4").performScrollTo().performTextInput("1234")
        compose.onNodeWithTag("asset-save").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("asset-cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(2, canceled)
        assertTrue(beta.assets().isEmpty())
    }
    @Test fun manualAccountSavesWithoutSmsAndSurvivesRestart() {
        var saved = false
        showEditor(onSaved = { saved = true })
        compose.onNodeWithTag("asset-last4").performScrollTo().performTextInput("١٢٣٤")
        confirmOwnership()
        compose.onNodeWithTag("asset-save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(saved) }
        val original = beta.assets().single()
        beta.close()
        beta = BetaContainer(context)
        assertEquals(original, beta.assets().single())
        assertEquals(AssetKind.ACCOUNT, original.kind)
        assertEquals("1234", original.last4)
    }
    @Test fun restoredDraftRetryKeepsItsStableIdentityWithoutDuplicateAsset() {
        val restoration = StateRestorationTester(compose)
        val savedIds = mutableListOf<AssetId>()
        restoration.setContent {
            MaterialTheme {
                BetaShell(0, false, editor = {
                    BetaAssetEditor(bank, null, emptyList(), false, false,
                        onSave = { savedIds += beta.saveManualAsset(it).id }, onCancel = {})
                }, onReturn = {}, onReset = {}, onBackup = {}, onRestore = {})
            }
        }
        compose.onNodeWithTag("asset-last4").performScrollTo().performTextInput("1234")
        confirmOwnership()
        compose.onNodeWithTag("asset-save").assertIsDisplayed().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("asset-owned-confirm").assertIsOn()
        compose.onNodeWithTag("asset-save").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, savedIds.size) }
        assertEquals(savedIds.first(), savedIds.last())
        assertEquals(1, beta.assets().size)
    }
    @Test fun existingDebitCanBeConfiguredAsSupplementaryWithStableIdAndFacilityLink() {
        beta.saveManualAsset(ManualAssetDraft(id("account"), AssetKind.ACCOUNT, "1111", ownedConfirmed = true))
        beta.saveManualAsset(ManualAssetDraft(id("facility"), AssetKind.CREDIT_FACILITY, "", ownedConfirmed = true))
        beta.saveManualAsset(ManualAssetDraft(id("primary"), AssetKind.CREDIT_CARD, "2222", id("facility"), CreditCardRole.PRIMARY, true))
        val debit = beta.saveManualAsset(ManualAssetDraft(id("debit"), AssetKind.DEBIT_CARD, "3333", id("account"), ownedConfirmed = true))
        showEditor(debit)
        compose.onNodeWithTag("asset-kind-CREDIT_CARD").performScrollTo().performClick()
        compose.onNodeWithTag("asset-role-SUPPLEMENTARY").performScrollTo().performClick()
        compose.onNodeWithTag("asset-primary-primary").performScrollTo().performClick()
        confirmOwnership()
        compose.onNodeWithTag("asset-save").assertIsDisplayed().performClick()
        val changed = beta.assets().single { it.id == debit.id }
        assertEquals(AssetKind.CREDIT_CARD, changed.kind)
        assertEquals(CreditCardRole.SUPPLEMENTARY, changed.creditCardRole)
        assertEquals(id("facility"), changed.parentId)
        assertEquals(4, beta.assets().size)
        beta.backup()
        beta.saveManualAsset(ManualAssetDraft(changed.id, changed.kind, "9999", changed.parentId, changed.creditCardRole,
            ownedConfirmed = true, editing = true, expectedOriginal = changed))
        assertTrue(beta.restore())
        assertEquals(changed, beta.assets().single { it.id == changed.id })
        beta.close()
        beta = BetaContainer(context)
        assertEquals(changed, beta.assets().single { it.id == changed.id })
    }
    @Test fun manualLoanRequiresOwnershipAndPersistsWithoutAnSmsOrPosting() {
        var saved = false
        showEditor(onSaved = { saved = true })
        compose.onNodeWithTag("asset-kind-LOAN").performScrollTo().performClick()
        compose.onNodeWithTag("asset-last4").performScrollTo().performTextInput("١٢٣٤")
        compose.onNodeWithTag("asset-save").assertIsDisplayed().assertIsNotEnabled()
        assertTrue(beta.assets().isEmpty())
        confirmOwnership()
        compose.onNodeWithTag("asset-save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(saved) }
        val original = beta.assets().single()
        assertEquals(AssetKind.LOAN, original.kind)
        assertEquals("1234", original.last4)
        assertNull(original.parentId); assertNull(original.creditCardRole)
        beta.close(); beta = BetaContainer(context)
        assertEquals(original, beta.assets().single())
        assertTrue(beta.discoveryPage().proposals.isEmpty())
        val db = androidx.room.Room.databaseBuilder(context, com.baraa.masroof.lab.beta.data.BetaDatabase::class.java,
            com.baraa.masroof.lab.beta.data.BetaDatabase.NAME).build()
        try {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM beta_financial_transaction").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { db.close() }
    }

    @Test fun standaloneAmexKeepsPrivateFacilityAcrossBackupAndReopen() {
        beta.saveManualAsset(ManualAssetDraft(id("facility"), AssetKind.CREDIT_FACILITY, "", ownedConfirmed = true))
        val card = beta.saveManualAsset(ManualAssetDraft(id("card"), AssetKind.CREDIT_CARD, "4444", id("facility"), CreditCardRole.PRIMARY, true))
        showEditor(card)
        compose.onNodeWithTag("asset-network-AMEX").performScrollTo().performClick()
        compose.onNodeWithTag("asset-role-STANDALONE").performScrollTo().performClick()
        compose.onNodeWithTag("asset-save").performClick()
        val saved = beta.assets().single { it.id == card.id }
        assertEquals(CreditCardRole.STANDALONE, saved.creditCardRole)
        assertEquals(card.parentId, saved.parentId)
        assertEquals("AMEX", beta.assetMetadata()[saved.id]?.network)
        // A second card cannot attach to a private facility, even outside the UI.
        assertThrows(IllegalArgumentException::class.java) {
            beta.saveManualAsset(ManualAssetDraft(id("other"), AssetKind.CREDIT_CARD, "5555", saved.parentId, CreditCardRole.PRIMARY, true))
        }
        assertEquals(2, beta.assets().size)
        beta.backup()
        assertTrue(beta.restore())
        beta.close(); beta = BetaContainer(context)
        assertEquals(saved, beta.assets().single { it.id == card.id })
        assertEquals("AMEX", beta.assetMetadata()[saved.id]?.network)
    }
    @Test fun primaryWithSupplementaryCannotBecomeStandaloneOrDebit() {
        beta.saveManualAsset(ManualAssetDraft(id("facility"), AssetKind.CREDIT_FACILITY, "", ownedConfirmed = true))
        val primary = beta.saveManualAsset(ManualAssetDraft(id("primary"), AssetKind.CREDIT_CARD, "2222", id("facility"), CreditCardRole.PRIMARY, true))
        beta.saveManualAsset(ManualAssetDraft(id("child"), AssetKind.CREDIT_CARD, "3333", id("facility"), CreditCardRole.SUPPLEMENTARY, true))
        showEditor(primary)
        compose.onNodeWithTag("asset-role-STANDALONE").performScrollTo().performClick()
        compose.onNodeWithTag("asset-save").assertIsNotEnabled()
        compose.onNodeWithTag("asset-kind-DEBIT_CARD").performScrollTo().performClick()
        compose.onNodeWithTag("asset-save").assertIsNotEnabled()
        assertEquals(primary, beta.assets().single { it.id == primary.id })
    }
    @Test fun alreadyOwnedAccountCanChangeTypeWithoutRepeatingOwnership() {
        val account=beta.saveManualAsset(ManualAssetDraft(id("account"),AssetKind.ACCOUNT,"1234",ownedConfirmed=true))
        showEditor(account)
        compose.onNodeWithTag("asset-owned-confirm").assertDoesNotExist()
        compose.onNodeWithTag("asset-account-type-WALLET").performScrollTo().performClick()
        compose.onNodeWithTag("asset-save").assertIsEnabled().performClick()
        assertEquals(account,beta.assets().single())
        assertEquals("WALLET",beta.assetMetadata()[account.id]?.accountType)
    }
    @Test fun confirmedReferenceDoesNotRepeatMineAndNeedsAnExplicitUnknownCardType() {
        var saved: ManualAssetDraft? = null
        compose.setContent {com.baraa.masroof.ui.theme.MasroofTheme {
            BetaAssetEditor(bank,null,emptyList(),false,false,{saved=it},{},initialKind=AssetKind.DEBIT_CARD,
                initialSuffix="4444",ownershipConfirmed=true)
        }}
        compose.onNodeWithTag("asset-owned-confirm").assertDoesNotExist()
        compose.onNodeWithTag("asset-last4").assertDoesNotExist()
        compose.onNodeWithTag("asset-save").assertIsNotEnabled()
        compose.onNodeWithTag("asset-kind-CREDIT_CARD").performScrollTo().performClick()
        compose.onNodeWithTag("asset-save").assertIsEnabled().performClick()
        assertEquals("4444",requireNotNull(saved).last4)
        assertEquals(AssetKind.CREDIT_CARD,requireNotNull(saved).kind)
        assertTrue(requireNotNull(saved).ownedConfirmed)
        assertTrue(requireNotNull(saved).createCreditFacility)
    }
    @Test fun walletAccountTypePersistsAcrossReopen() {
        showEditor()
        compose.onNodeWithTag("asset-account-type-WALLET").performScrollTo().performClick()
        compose.onNodeWithTag("asset-last4").performScrollTo().performTextInput("1234")
        confirmOwnership()
        compose.onNodeWithTag("asset-save").performClick()
        val account = beta.assets().single()
        beta.close(); beta = BetaContainer(context)
        assertEquals("WALLET", beta.assetMetadata()[account.id]?.accountType)
    }

}
