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
    private fun reviewCardCandidate(): Pair<String,com.baraa.masroof.lab.beta.application.BetaOwnershipCandidate> {
        beta.acknowledgeStart()
        beta.saveManualAsset(ManualAssetDraft(id("review-account"),AssetKind.ACCOUNT,"1234",ownedConfirmed=true))
        val at=java.time.Instant.parse("2026-10-08T09:00:00Z")
        val evidence=(beta.importEvidence(com.baraa.masroof.lab.beta.application.evidence.EvidenceRequest("review-asset",com.baraa.masroof.lab.beta.application.evidence.EvidenceOrigin.EXPLICIT_HISTORY,setOf(bank)),
            listOf(com.baraa.masroof.lab.beta.application.evidence.EvidenceMessage("card","AlJazira","شراء عبر نقاط البيع\nبطاقة مدى: 5678\nلدى: TEST SHOP\nبمبلغ: SAR 25\nخصمت من حساب: 1234\nفي: 2026-10-08 12:00",at,com.baraa.masroof.lab.beta.application.evidence.ReceiptTimeSource.PROVIDER_TIMESTAMP))).single()
            as com.baraa.masroof.lab.beta.application.evidence.EvidenceResult.Stored).summary.id
        beta.processAutomaticEvidence()
        return evidence to beta.ownershipSnapshot().candidates.single {it.reference.kind==com.baraa.masroof.lab.beta.application.BetaOwnershipKind.CARD && it.reference.suffix=="5678"}
    }
    @Test fun reviewConfigurationRollsBackIfOwnershipCannotBindAndRejectsAChangedDecision() {
        val (_,candidate)=reviewCardCandidate()
        val before=beta.assets();val metadata=beta.assetMetadata()
        val invalid=ManualAssetDraft(id("review-card"),AssetKind.DEBIT_CARD,"9999",id("review-account"),ownedConfirmed=true,
            metadata=com.baraa.masroof.lab.beta.application.BetaAssetMetadata(network="MADA"))
        assertTrue(runCatching {beta.saveReviewAsset(invalid,candidate.reference,candidate.token)}.isFailure)
        assertEquals(before,beta.assets());assertEquals(metadata,beta.assetMetadata())
        beta.decideOwnership(candidate,false)
        assertTrue(runCatching {beta.saveReviewAsset(invalid.copy(last4="5678"),candidate.reference,candidate.token)}.isFailure)
        assertEquals(before,beta.assets())
        assertEquals(com.baraa.masroof.lab.beta.application.BetaOwnershipStatus.EXTERNAL,beta.ownershipSnapshot().candidates.single {it.reference==candidate.reference}.status)
    }
    @Test fun reviewConfigurationAndOwnershipCommitBeforeOneAutomaticPosting() {
        val (evidence,candidate)=reviewCardCandidate()
        val saved=beta.saveReviewAsset(ManualAssetDraft(id("review-card"),AssetKind.DEBIT_CARD,"5678",id("review-account"),ownedConfirmed=true,
            metadata=com.baraa.masroof.lab.beta.application.BetaAssetMetadata(network="MADA")),candidate.reference,candidate.token)
        assertEquals(id("review-account"),saved.parentId)
        assertEquals(com.baraa.masroof.lab.beta.application.BetaOwnershipStatus.OWNED,beta.ownershipSnapshot().candidates.single {it.reference==candidate.reference}.status)
        beta.processAutomaticEvidence();beta.processAutomaticEvidence()
        val key=com.baraa.masroof.lab.finance.posting.PostingKey(Environment.BETA,"evidence:$evidence")
        assertEquals("review=${beta.reviewDetail(evidence).reasons}; blocked=${beta.financialApprovalPreview(evidence)?.blockedReason}",com.baraa.masroof.lab.finance.posting.FinancialType.EXPENSE,beta.financialPosting(key)?.command?.type)
        assertEquals(1,beta.financialHistory(key).size)
    }

    @Test fun restoredAccountDraftKeepsItsOriginalMetadataRevision() = metadataRevisionSurvivesRestore(false)
    @Test fun restoredCardDraftKeepsItsOriginalMetadataRevision() = metadataRevisionSurvivesRestore(true)
    private fun metadataRevisionSurvivesRestore(card: Boolean) {
        val asset=if(card) {
            beta.saveManualAsset(ManualAssetDraft(id("facility"),AssetKind.CREDIT_FACILITY,"",ownedConfirmed=true))
            beta.saveManualAsset(ManualAssetDraft(id("edited"),AssetKind.CREDIT_CARD,"7271",id("facility"),CreditCardRole.PRIMARY,true,
                metadata=com.baraa.masroof.lab.beta.application.BetaAssetMetadata(displayName="Original",network="VISA")))
        } else beta.saveManualAsset(ManualAssetDraft(id("edited"),AssetKind.ACCOUNT,"3001",ownedConfirmed=true,
            metadata=com.baraa.masroof.lab.beta.application.BetaAssetMetadata(displayName="Original",accountType="CURRENT")))
        val original=beta.assetMetadata().getValue(asset.id)
        val shown=mutableStateOf(original)
        var submitted: ManualAssetDraft?=null
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme {
            BetaAssetEditor(bank,asset,beta.assets(),false,false,onSave={submitted=it},onCancel={},
                metadata=shown.value,registryMetadata=beta.assetMetadata())
        }}
        val latest=original.copy(displayName="Concurrent name",network=if(card) "MASTERCARD" else null,accountType=if(card) null else "SAVINGS")
        beta.saveManualAsset(ManualAssetDraft(asset.id,asset.kind,asset.last4.orEmpty(),asset.parentId,asset.creditCardRole,true,true,asset,metadata=latest))
        compose.runOnIdle {shown.value=beta.assetMetadata().getValue(asset.id)}
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("asset-save").assertIsEnabled().performClick()
        assertEquals(original.revision,checkNotNull(checkNotNull(submitted).metadata).revision)
        assertTrue(runCatching {beta.saveManualAsset(checkNotNull(submitted))}.isFailure)
        assertEquals("Concurrent name",beta.assetMetadata().getValue(asset.id).displayName)
        assertEquals(if(card) "MASTERCARD" else null,beta.assetMetadata().getValue(asset.id).network)
        assertEquals(if(card) null else "SAVINGS",beta.assetMetadata().getValue(asset.id).accountType)
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
