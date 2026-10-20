package com.baraa.masroof.lab.beta.presentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import com.baraa.masroof.ui.theme.*
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BetaReviewResolutionScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val bank=BankId("BANK_ALJAZIRA")
    private fun id(s: String)=AssetId(Environment.BETA,bank,s)
    private val account=OwnedAsset(id("account"),AssetKind.ACCOUNT,"3001")
    private val one=OwnedAsset(id("card-one"),AssetKind.CREDIT_CARD,"7271",id("facility-one"),CreditCardRole.PRIMARY)
    private val two=OwnedAsset(id("card-two"),AssetKind.CREDIT_CARD,"8842",id("facility-two"),CreditCardRole.PRIMARY)
    private val at=Instant.parse("2026-10-08T10:00:00Z")
    private fun detail(): BetaReviewDetail {
        val assets=listOf(account,OwnedAsset(one.parentId!!,AssetKind.CREDIT_FACILITY),one,OwnedAsset(two.parentId!!,AssetKind.CREDIT_FACILITY),two)
        val item=BetaFinancialReviewItem("fixture",PostingKey(Environment.BETA,"evidence:fixture"),"archive-one","REVIEW","CONFIRM_CARD",null)
        return BetaReviewDetail(BetaInboxRow("fixture","ALJAZIRA",at,bank.value,"CARD_PAYMENT","50","SAR",null,"CONFIRM_CARD",true,"synthetic"),
            BetaFinancialApprovalPreview(item,Money.of("50",Currency.SAR),TransactionTime(at,ZoneId.of("Asia/Riyadh")),assets,"registry-one",null,null,false),
            emptyList(),listOf(FinancialType.CREDIT_CARD_PAYMENT),account.id,null,null,false,emptyMap(),emptySet())
    }
    private fun click(tag: String) {
        if(tag in setOf("review-asset-apply","review-asset-cancel")) compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun choose(id: AssetId)=click("review-endpoint-${betaAssetChoiceKey(id)}")
    @Test fun endpointDraftRestoresAndCancelDiscardsWithoutApproving() {
        val restoration=StateRestorationTester(compose);var submitted: BetaFinancialApproval?=null
        restoration.setContent {BetaLocaleProvider("en") {MasroofTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BetaReviewResolutionActions(detail(),false,{submitted=it},{},{})
        }}}}
        click("review-resolve-CREDIT_CARD_PAYMENT");click("review-select-destination");choose(one.id)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("review-endpoint-${betaAssetChoiceKey(one.id)}").assertIsOn()
        click("review-asset-cancel");assertNull(submitted)
        compose.onNodeWithTag("review-confirm-resolution").assertIsNotEnabled()
        click("review-select-destination");choose(two.id);click("review-asset-apply");click("review-confirm-resolution")
        assertEquals(two.id,submitted?.destination);assertEquals(account.id,submitted?.source)
        assertEquals("archive-one",submitted?.archiveRevision)
    }
    @Test fun aRestoredEndpointChoiceCannotAdoptANewerArchiveOrRegistry() {
        val shown=mutableStateOf(detail());var submitted: BetaFinancialApproval?=null
        val restoration=StateRestorationTester(compose)
        restoration.setContent {BetaLocaleProvider("en") {MasroofTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BetaReviewResolutionActions(shown.value,false,{submitted=it},{},{})
        }}}}
        click("review-resolve-CREDIT_CARD_PAYMENT");click("review-select-destination");choose(one.id);click("review-asset-apply")
        val original=shown.value
        compose.runOnIdle {shown.value=original.copy(preview=original.preview!!.copy(item=original.preview.item.copy(archiveRevision="archive-two"),registryRevision="registry-two",amount=Money.of("99",Currency.SAR)))}
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("review-draft-stale").assertExists()
        compose.onNodeWithTag("review-confirm-resolution").assertIsNotEnabled();assertNull(submitted)
        click("review-reset-draft");click("review-resolve-CREDIT_CARD_PAYMENT");click("review-select-destination");choose(two.id);click("review-asset-apply");click("review-confirm-resolution")
        assertEquals("archive-two",submitted?.archiveRevision);assertEquals("registry-two",submitted?.expectedRegistryRevision)
        assertEquals(two.id,submitted?.destination)
    }
    @Test fun reviewWithAnUnknownCurrencyDoesNotInventSar() {
        val unknown=detail().let {it.copy(row=it.row.copy(currency=null),reasons=setOf("MISSING_CURRENCY"))}
        compose.setContent {BetaLocaleProvider("en") {MasroofTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BetaReviewDetailContent(betaReviewDetailView(unknown,false))
        }}}}
        compose.onNodeWithText("50 SAR").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.beta_approved_dashboard_value_unavailable)).assertExists()
    }
}
