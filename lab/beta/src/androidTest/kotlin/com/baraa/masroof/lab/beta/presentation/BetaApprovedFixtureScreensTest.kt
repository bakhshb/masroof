package com.baraa.masroof.lab.beta.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.baraa.masroof.lab.beta.presentation.approved.*
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.lab.beta.R
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.application.UnavailableBetaDashboardControls
import com.baraa.masroof.lab.beta.application.BetaDashboardWorkflow
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.DashboardProduction
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.*
import java.math.BigDecimal

/**
 * The frozen card/loan facts from production FidelityFixtures at 0d86c5d3.
 * This compares rendering and navigation. It is not an extraction/ledger oracle.
 */
class BetaApprovedFixtureScreensTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val zone=ZoneId.of("Asia/Riyadh")
    @Test fun sameFrozenProductionFactsRenderInBothLanguagesAndThemes() {
        val page=mutableStateOf("cards")
        val language=mutableStateOf("ar")
        val dark=mutableStateOf(false)
        val workflow=BetaDashboardWorkflow({query,_ -> DashboardProduction(BetaFrozenProductionRead.snapshot(query),emptySet(),null)},
            Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"),zone),zone)
        compose.setContent {
            BetaLocaleProvider(language.value) {MasroofTheme(darkTheme=dark.value) {
                key(page.value,language.value,dark.value) {
                    var listShare by remember {mutableStateOf<BetaListShare?>(null)}
                    var title by remember {mutableStateOf<String?>(null)}
                    val preferencePage=page.value=="preferences"
                    val reviewPage=page.value=="review"
                    val registryPage=page.value in setOf("myAccounts","myCards","myLoans")
                    val commitmentPage=page.value in setOf("myCommitments","commitmentEditor")
                    val commitment=com.baraa.masroof.lab.beta.application.BetaRecurringCommitment("internet","الإنترنت",Money.of("200",Currency.SAR),5,YearMonth.of(2026,10),
                        transactionDate=LocalDate.parse("2026-10-05"),sourceTransactionId="ref-12")
                    BetaShell(0,false,showReturn=false,showMaintenanceActions=false,notificationCount=3,
                        secondaryTitle=if(commitmentPage) stringResource(if(page.value=="myCommitments") R.string.beta_commitment_reference_manage_commitments else R.string.beta_commitment_settings_commitment_edit_title) else if(reviewPage) stringResource(R.string.beta_nav_review) else if(preferencePage) stringResource(R.string.beta_approved_reference_preferences_title) else title,
                        secondaryActions={if(page.value=="transactions") listShare?.let {BetaListShareAction(it)}},
                        onNavigateBack={},selectedHomeTab=if(reviewPage) "REVIEW" else if(preferencePage || registryPage || commitmentPage) "SETTINGS" else if(page.value=="transactions") "TRANSACTIONS" else if(page.value=="home") "HOME" else "NONE",onOpenHome={},dashboardOwnsScroll=true,editorOwnsScroll=reviewPage || commitmentPage,
                        editor=if(commitmentPage) ({
                            if(page.value=="myCommitments") BetaCommitmentsContent(listOf(commitment),emptyList(),"ACTIVE",false,{},{},{},{})
                            else BetaCommitmentEditorContent(commitment,false,{},{},{})
                        }) else if(reviewPage) ({Column(Modifier.fillMaxSize().testTag("review-fixture-ready")) {
                            ApprovedReviewListContent(listOf(
                                ApprovedReviewListItem("حوالة تحتاج مطابقة",stringResource(R.string.beta_approved_review_pending_match),false,"750 ر.س","8 أكتوبر · بنك الجزيرة","multiple_candidates",stringResource(R.string.beta_complete_review_evidence)),
                                ApprovedReviewListItem("بطاقة غير مؤكدة الملكية",stringResource(R.string.beta_review_parity_kind),false,null,"8 أكتوبر · بنك الجزيرة","unknown_ownership",stringResource(R.string.beta_complete_review_evidence))),false,true,{},{},emptyContent={})
                        }}) else if(preferencePage) ({BetaSettingsHubPanel("APP",{},emptyList(),emptyList(),UnavailableBetaDashboardControls,false,
                            {},{},{},theme=if(dark.value) "DARK" else "LIGHT",language=language.value)}) else if(registryPage) ({
                            val bank=BankId("BANK_ALJAZIRA")
                            fun id(value: String)=AssetId(Environment.BETA,bank,value)
                            val assets=listOf(OwnedAsset(id("3001"),AssetKind.ACCOUNT,"3001"),OwnedAsset(id("5002"),AssetKind.ACCOUNT,"5002"),
                                OwnedAsset(id("facility"),AssetKind.CREDIT_FACILITY,null),
                                OwnedAsset(id("7271"),AssetKind.CREDIT_CARD,"7271",id("facility"),CreditCardRole.PRIMARY),
                                OwnedAsset(id("8842"),AssetKind.CREDIT_CARD,"8842",id("facility"),CreditCardRole.SUPPLEMENTARY),
                                OwnedAsset(id("4421"),AssetKind.DEBIT_CARD,"4421",id("3001")),OwnedAsset(id("personal"),AssetKind.LOAN,null))
                            val metadata=mapOf(
                                id("3001") to com.baraa.masroof.lab.beta.application.BetaAssetMetadata(displayName="الجاري",accountType="CURRENT"),
                                id("5002") to com.baraa.masroof.lab.beta.application.BetaAssetMetadata(displayName="الادخار",accountType="SAVINGS"),
                                id("7271") to com.baraa.masroof.lab.beta.application.BetaAssetMetadata(displayName="البطاقة الأساسية",network="VISA"),
                                id("8842") to com.baraa.masroof.lab.beta.application.BetaAssetMetadata(displayName="البطاقة الفرعية",network="VISA"),
                                id("4421") to com.baraa.masroof.lab.beta.application.BetaAssetMetadata(displayName="مدى",network="MADA"),
                                id("personal") to com.baraa.masroof.lab.beta.application.BetaAssetMetadata(displayName="قرض شخصي",loanType="PERSONAL"))
                            BetaSettingsHubPanel(when(page.value) {"myAccounts"->"ACCOUNTS";"myLoans"->"LOANS";else->"CARDS"},{},
                                listOf(com.baraa.masroof.lab.beta.application.BetaBankChoice(bank,null)),assets,UnavailableBetaDashboardControls,false,{},{},{},
                                // Source account fixture has no bankTree, hence no linked debit rows.
                                tracking=assets.filterNot {page.value=="myAccounts" && it.kind==AssetKind.DEBIT_CARD}.map {com.baraa.masroof.lab.beta.application.BetaTrackedAsset(it,com.baraa.masroof.lab.beta.application.BetaOwnershipStatus.OWNED,0)},
                                metadata=metadata,onRegistryTitle={title=it})
                        }) else null,
                        dashboard=({BetaDashboardPanel(workflow,ownsScroll=true,onListShare={listShare=it},showInternalHeader=false,
                            requestedRoute=when(page.value) {"commitments"->"COMMITMENTS";"transactions"->"TRANSACTIONS";"loans","loanDetail" -> "LOANS";"merchants" -> "MERCHANTS";"daily" -> "DAILY";"home" -> "OVERVIEW";"accounts","accountDetail" -> "ACCOUNTS";else -> "CARDS"},onPageTitle={title=it})}),
                        onReturn={},onReset={},onBackup={},onRestore={})
                }
            }}
        }
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"lab-approved-fixture").apply {mkdirs()}
        for(lang in listOf("ar","en")) for(night in listOf(false,true)) for(destination in listOf("cards","cardDetail","loans","loanDetail","preferences","merchants","daily","accounts","accountDetail","home","review","myAccounts","myCards","myLoans","transactions","myCommitments","commitmentEditor","commitments")) {
            compose.runOnIdle {language.value=lang;dark.value=night;page.value=destination}
            compose.waitUntil(15000) {compose.onAllNodesWithTag(if(destination=="myCommitments") "commitments-list" else if(destination=="commitmentEditor") "commitment-editor" else if(destination=="review") "review-fixture-ready" else if(destination=="preferences" || destination.startsWith("my")) "beta-settings-panel" else "dashboard-ready").fetchSemanticsNodes().isNotEmpty()}
            if(destination in setOf("cardDetail","loanDetail","accountDetail")) {
                val target=when(destination) {"cardDetail" -> "8842";"accountDetail" -> "3001";else -> "personal"}
                compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-$target").performScrollTo().performClick()
                val detail=when(destination) {"cardDetail" -> "beta-credit-detail";"accountDetail" -> "beta-account-detail";else -> "beta-loan-detail"}
                compose.waitUntil(15000) {compose.onAllNodesWithTag(detail).fetchSemanticsNodes().isNotEmpty()}
            }
            compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(250)
            val texts=compose.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes()
                .flatMap {it.config.getOrElse(SemanticsProperties.Text) {emptyList()}}.map {it.text}
            assertTrue(texts.size>4)
            assertTrue(texts.none {Regex("[\\u0660-\\u0669\\u06F0-\\u06F9]").containsMatchIn(it)})
            fun capture(suffix: String) {
                val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
                File(directory,"$destination-$lang-${if(night) "dark" else "light"}$suffix.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            capture("")
            val vertical=SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            if(compose.onAllNodes(vertical).fetchSemanticsNodes().isNotEmpty()) {
                compose.onAllNodes(vertical)[0].performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,100000f)}
                compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(250)
            }
            capture("-bottom")
        }
    }
}
